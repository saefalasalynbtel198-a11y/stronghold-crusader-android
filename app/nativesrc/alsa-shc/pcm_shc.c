/*
 * SHC Port audio: an ALSA PCM plugin that sends the game's sound to the SHC Port app, which plays it through
 * Android's audio output (Android does not let apps open ALSA sound devices).
 *
 *   game -> Miles/DirectSound -> Wine mmdevapi -> winealsa -> (Box64 libasound wrapper) -> native libasound
 *        -> this plugin -> local socket -> app (core/AudioStreamPump -> AudioTrack)
 *
 * asound.conf (written by the app, selected with ALSA_CONFIG_PATH):
 *   pcm_type.shc { lib "/data/user/0/<pkg>/files/audio/libasound_module_pcm_shc.so" }
 *   pcm.!default { type shc socket "@shc-audio-1234" }      # '@' = abstract socket name, else a file path
 *
 * Protocol on the socket: the plugin sends a 16-byte header ("SHC1", rate, channels, bits=16; little-endian u32) and
 * then interleaved signed 16-bit little-endian PCM. The app answers with little-endian u64 counters: the total number
 * of frames it has accepted into the Android output so far. Those counters are this PCM's hardware position, so the
 * game is paced by the real output. If the app is not reachable (or goes away), the position advances with the
 * monotonic clock instead and the data is dropped: the game keeps running as with a silent sound card.
 *
 * Build: gcc -shared -fPIC -DPIC -O2 -Wall -Werror -o libasound_module_pcm_shc.so pcm_shc.c -lasound
 */
#define _GNU_SOURCE
#include <alsa/asoundlib.h>
#include <alsa/pcm_external.h>
#include <errno.h>
#include <stdarg.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

typedef struct {
    snd_pcm_ioplug_t io;
    char *socket_name;
    int fd;                      /* connection to the app, -1 when not connected */
    int dummy[2];                /* pipe whose write end is the poll descriptor (always writable) */
    unsigned rate, channels, frame_bytes;
    unsigned conn_rate, conn_channels;
    uint64_t sent;               /* frames sent on this connection */
    uint64_t acked;              /* frames the app has accepted on this connection (its last counter) */
    unsigned char ack_buf[8];
    unsigned ack_len;
    uint64_t base;               /* connection frame count at the last prepare (start of this run) */
    uint64_t clock_frames;       /* without the app: frames "played" by the clock since prepare */
    struct timespec clock_t0;
    int clock_running;
    int debug;
} shc_t;

static void dbg(shc_t *s, const char *fmt, ...) {
    if (!s->debug) return;
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "shc-audio: ");
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
}

static void put32(unsigned char *p, uint32_t v) {
    p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24;
}

static void disconnect(shc_t *s, const char *why) {
    if (s->fd >= 0) {
        dbg(s, "disconnected (%s)", why);
        close(s->fd);
    }
    s->fd = -1;
    s->ack_len = 0;
}

/* Connects to the app and sends the header; on failure the PCM runs on the clock. */
static void connect_app(shc_t *s) {
    disconnect(s, "reconnect");
    s->sent = s->acked = s->base = 0;
    if (!s->socket_name || !*s->socket_name) return;
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return;
    struct sockaddr_un a;
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    socklen_t len;
    size_t n = strlen(s->socket_name);
    if (s->socket_name[0] == '@') {               /* abstract namespace (Android LocalServerSocket) */
        if (n > sizeof a.sun_path) { close(fd); return; }
        memcpy(a.sun_path + 1, s->socket_name + 1, n - 1);
        len = offsetof(struct sockaddr_un, sun_path) + n;
    } else {
        if (n >= sizeof a.sun_path) { close(fd); return; }
        memcpy(a.sun_path, s->socket_name, n);
        len = offsetof(struct sockaddr_un, sun_path) + n + 1;
    }
    if (connect(fd, (struct sockaddr *)&a, len) < 0) {
        dbg(s, "no app at %s (%s): clock only", s->socket_name, strerror(errno));
        close(fd);
        return;
    }
    int sndbuf = 512 * 1024;
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof sndbuf);
    unsigned char h[16];
    memcpy(h, "SHC1", 4);
    put32(h + 4, s->rate);
    put32(h + 8, s->channels);
    put32(h + 12, 16);
    if (send(fd, h, sizeof h, MSG_NOSIGNAL) != (ssize_t)sizeof h) {
        close(fd);
        return;
    }
    s->fd = fd;
    s->conn_rate = s->rate;
    s->conn_channels = s->channels;
    dbg(s, "connected to %s: %u Hz, %u channels, 16-bit", s->socket_name, s->rate, s->channels);
}

/* Reads the app's counters that have arrived (non-blocking). */
static void read_acks(shc_t *s) {
    while (s->fd >= 0) {
        ssize_t r = recv(s->fd, s->ack_buf + s->ack_len, 8 - s->ack_len, MSG_DONTWAIT);
        if (r > 0) {
            s->ack_len += r;
            if (s->ack_len == 8) {
                uint64_t v = 0;
                for (int i = 7; i >= 0; i--) v = v << 8 | s->ack_buf[i];
                if (v > s->acked) s->acked = v;
                s->ack_len = 0;
            }
        } else if (r == 0) {
            disconnect(s, "app closed the stream");
        } else {
            if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) disconnect(s, strerror(errno));
            return;
        }
    }
}

static uint64_t clock_now(shc_t *s) {
    if (!s->clock_running) return s->clock_frames;
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    double sec = (t.tv_sec - s->clock_t0.tv_sec) + (t.tv_nsec - s->clock_t0.tv_nsec) / 1e9;
    return s->clock_frames + (uint64_t)(sec * s->rate);
}

/* Frames played (accepted by the app, or by the clock) since the last prepare, never past what was written. */
static uint64_t played(shc_t *s) {
    uint64_t appl = s->io.appl_ptr;          /* frames written since prepare (ioplug's application pointer) */
    uint64_t p;
    if (s->fd >= 0) {
        read_acks(s);
    }
    if (s->fd >= 0) p = s->acked > s->base ? s->acked - s->base : 0;
    else p = clock_now(s);
    return p < appl ? p : appl;
}

/*
 * Position since prepare, not wrapped at the buffer size (SND_PCM_IOPLUG_FLAG_BOUNDARY_WA): the app's counters can
 * advance by exactly one buffer between two calls, which a position modulo the buffer size could not show.
 */
static snd_pcm_sframes_t shc_pointer(snd_pcm_ioplug_t *io) {
    shc_t *s = io->private_data;
    return (snd_pcm_sframes_t)played(s);
}

static snd_pcm_sframes_t shc_transfer(snd_pcm_ioplug_t *io, const snd_pcm_channel_area_t *areas,
                                      snd_pcm_uframes_t offset, snd_pcm_uframes_t size) {
    shc_t *s = io->private_data;
    if (s->fd >= 0) {
        const char *buf = (const char *)areas->addr + (areas->first + areas->step * offset) / 8;
        size_t left = size * s->frame_bytes;
        while (left > 0) {
            ssize_t w = send(s->fd, buf, left, MSG_NOSIGNAL);
            if (w < 0) {
                if (errno == EINTR) continue;
                disconnect(s, strerror(errno));
                /* continue on the clock from the position reached so far */
                s->clock_frames = s->acked > s->base ? s->acked - s->base : 0;
                clock_gettime(CLOCK_MONOTONIC, &s->clock_t0);
                s->clock_running = 1;
                break;
            }
            buf += w;
            left -= w;
        }
        if (s->fd >= 0) s->sent += size;
    }
    return size;
}

static int shc_start(snd_pcm_ioplug_t *io) {
    shc_t *s = io->private_data;
    clock_gettime(CLOCK_MONOTONIC, &s->clock_t0);
    s->clock_running = 1;
    return 0;
}

static int shc_stop(snd_pcm_ioplug_t *io) {
    shc_t *s = io->private_data;
    s->clock_frames = clock_now(s);
    s->clock_running = 0;
    return 0;
}

static int shc_prepare(snd_pcm_ioplug_t *io) {
    shc_t *s = io->private_data;
    if (s->fd >= 0 && (s->conn_rate != s->rate || s->conn_channels != s->channels)) disconnect(s, "format changed");
    if (s->fd < 0) connect_app(s);
    else read_acks(s);
    /* a new run: everything sent before counts as already played */
    s->base = s->sent;
    if (s->acked > s->sent) s->acked = s->sent;
    s->clock_frames = 0;
    s->clock_running = 0;
    return 0;
}

static int shc_hw_params(snd_pcm_ioplug_t *io, snd_pcm_hw_params_t *params) {
    shc_t *s = io->private_data;
    (void)params;
    s->rate = io->rate;
    s->channels = io->channels;
    s->frame_bytes = io->channels * 2;
    dbg(s, "hw_params: %u Hz, %u channels, buffer %lu, period %lu frames", io->rate, io->channels,
        (unsigned long)io->buffer_size, (unsigned long)io->period_size);
    return 0;
}

static int shc_delay(snd_pcm_ioplug_t *io, snd_pcm_sframes_t *delayp) {
    shc_t *s = io->private_data;
    uint64_t p = played(s);
    *delayp = (snd_pcm_sframes_t)(io->appl_ptr > p ? io->appl_ptr - p : 0);
    return 0;
}

static int shc_close(snd_pcm_ioplug_t *io) {
    shc_t *s = io->private_data;
    disconnect(s, "closed");
    close(s->dummy[0]);
    close(s->dummy[1]);
    free(s->socket_name);
    free(s);
    return 0;
}

static const snd_pcm_ioplug_callback_t shc_callback = {
    .start = shc_start,
    .stop = shc_stop,
    .pointer = shc_pointer,
    .transfer = shc_transfer,
    .close = shc_close,
    .hw_params = shc_hw_params,
    .prepare = shc_prepare,
    .delay = shc_delay,
};

static int set_constraints(snd_pcm_ioplug_t *io) {
    static const unsigned int access[] = {SND_PCM_ACCESS_RW_INTERLEAVED};
    static const unsigned int formats[] = {SND_PCM_FORMAT_S16_LE};
    int err;
    if ((err = snd_pcm_ioplug_set_param_list(io, SND_PCM_IOPLUG_HW_ACCESS, 1, access)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_list(io, SND_PCM_IOPLUG_HW_FORMAT, 1, formats)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_CHANNELS, 1, 2)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_RATE, 8000, 48000)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_PERIOD_BYTES, 64, 256 * 1024)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_BUFFER_BYTES, 256, 1024 * 1024)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_PERIODS, 2, 1024)) < 0) return err;
    return 0;
}

SND_PCM_PLUGIN_DEFINE_FUNC(shc) {
    snd_config_iterator_t i, next;
    const char *sock = NULL;
    int err;
    (void)root;
    snd_config_for_each(i, next, conf) {
        snd_config_t *n = snd_config_iterator_entry(i);
        const char *id;
        if (snd_config_get_id(n, &id) < 0) continue;
        if (!strcmp(id, "comment") || !strcmp(id, "type") || !strcmp(id, "hint")) continue;
        if (!strcmp(id, "socket")) {
            if (snd_config_get_string(n, &sock) < 0) {
                SNDERR("socket must be a string");
                return -EINVAL;
            }
            continue;
        }
        SNDERR("Unknown field %s", id);
        return -EINVAL;
    }
    if (stream != SND_PCM_STREAM_PLAYBACK) return -ENOTSUP;   /* playback only */

    shc_t *s = calloc(1, sizeof *s);
    if (!s) return -ENOMEM;
    s->fd = -1;
    s->debug = getenv("SHC_AUDIO_DEBUG") != NULL;
    s->socket_name = strdup(sock ? sock : "");
    if (pipe2(s->dummy, O_CLOEXEC | O_NONBLOCK) < 0) {
        free(s->socket_name);
        free(s);
        return -errno;
    }
    s->io.version = SND_PCM_IOPLUG_VERSION;
    s->io.name = "SHC Port audio (Android)";
    s->io.mmap_rw = 0;
    s->io.callback = &shc_callback;
    s->io.private_data = s;
    s->io.poll_fd = s->dummy[1];
    s->io.poll_events = POLLOUT;
    s->io.flags = SND_PCM_IOPLUG_FLAG_BOUNDARY_WA;
    if ((err = snd_pcm_ioplug_create(&s->io, name, stream, mode)) < 0) {
        close(s->dummy[0]);
        close(s->dummy[1]);
        free(s->socket_name);
        free(s);
        return err;
    }
    if ((err = set_constraints(&s->io)) < 0) {
        snd_pcm_ioplug_delete(&s->io);
        return err;
    }
    *pcmp = s->io.pcm;
    return 0;
}

SND_PCM_PLUGIN_SYMBOL(shc);
