/*
 * Drives ALSA the way Wine's winealsa does (non-blocking open of "default", S16 stereo 48 kHz, ~10 ms periods, 4-period
 * buffer, start threshold 1, stop threshold = buffer, writes every 10 ms what avail allows, XRUN recovery) and plays a
 * tone for <seconds>. Prints how long it took: with a working plugin the elapsed time follows the output's real rate.
 * Usage: alsa-client-test <seconds> <frequency> [float]
 */
#include <alsa/asoundlib.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
    double secs = argc > 1 ? atof(argv[1]) : 3;
    double freq = argc > 2 ? atof(argv[2]) : 440;
    int fl = argc > 3 && !strcmp(argv[3], "float");
    snd_pcm_t *pcm;
    int err;
    if ((err = snd_pcm_open(&pcm, "default", SND_PCM_STREAM_PLAYBACK, SND_PCM_NONBLOCK)) < 0) {
        printf("alsa-test: open failed: %s\n", snd_strerror(err));
        return 2;
    }
    snd_pcm_hw_params_t *hw;
    snd_pcm_hw_params_alloca(&hw);
    snd_pcm_hw_params_any(pcm, hw);
    snd_pcm_format_mask_t *fm;
    snd_pcm_format_mask_alloca(&fm);
    snd_pcm_hw_params_get_format_mask(hw, fm);
    unsigned rmax = 0, cmax = 0;
    snd_pcm_hw_params_get_rate_max(hw, &rmax, NULL);
    snd_pcm_hw_params_get_channels_max(hw, &cmax);
    printf("alsa-test: device offers S16_LE=%d FLOAT_LE=%d, rate max %u, channels max %u\n",
           snd_pcm_format_mask_test(fm, SND_PCM_FORMAT_S16_LE), snd_pcm_format_mask_test(fm, SND_PCM_FORMAT_FLOAT_LE), rmax, cmax);
    unsigned rate = 48000, period_us = 10000, periods = 4;
    snd_pcm_hw_params_set_access(pcm, hw, SND_PCM_ACCESS_RW_INTERLEAVED);
    if ((err = snd_pcm_hw_params_set_format(pcm, hw, fl ? SND_PCM_FORMAT_FLOAT_LE : SND_PCM_FORMAT_S16_LE)) < 0) {
        printf("alsa-test: format not accepted: %s\n", snd_strerror(err));
        return 4;
    }
    snd_pcm_hw_params_set_rate_near(pcm, hw, &rate, NULL);
    snd_pcm_hw_params_set_channels(pcm, hw, 2);
    snd_pcm_hw_params_set_period_time_near(pcm, hw, &period_us, NULL);
    snd_pcm_hw_params_set_periods_near(pcm, hw, &periods, NULL);
    if ((err = snd_pcm_hw_params(pcm, hw)) < 0) {
        printf("alsa-test: hw_params failed: %s\n", snd_strerror(err));
        return 3;
    }
    snd_pcm_uframes_t buf, per;
    snd_pcm_hw_params_get_buffer_size(hw, &buf);
    snd_pcm_hw_params_get_period_size(hw, &per, NULL);
    snd_pcm_sw_params_t *sw;
    snd_pcm_sw_params_alloca(&sw);
    snd_pcm_sw_params_current(pcm, sw);
    snd_pcm_sw_params_set_start_threshold(pcm, sw, 1);
    snd_pcm_sw_params_set_stop_threshold(pcm, sw, buf);
    snd_pcm_sw_params(pcm, sw);
    snd_pcm_prepare(pcm);
    printf("alsa-test: %u Hz, buffer %lu, period %lu frames\n", rate, (unsigned long)buf, (unsigned long)per);
    long total = (long)(secs * rate), done = 0, xruns = 0, phase = 0;
    short *chunk = malloc(buf * 4);
    float *fchunk = malloc(buf * 8);
    double t0 = now();
    while (done < total) {
        snd_pcm_sframes_t avail = snd_pcm_avail_update(pcm);
        if (snd_pcm_state(pcm) == SND_PCM_STATE_XRUN || avail == -EPIPE) {
            xruns++;
            snd_pcm_recover(pcm, -EPIPE, 1);
            snd_pcm_prepare(pcm);
            avail = buf;
        }
        if (avail > 0) {
            long n = avail;
            if (n > total - done) n = total - done;
            for (long i = 0; i < n; i++, phase++) {
                double x = sin(2 * M_PI * freq * phase / rate);
                chunk[2 * i] = chunk[2 * i + 1] = (short)(12000 * x);
                fchunk[2 * i] = fchunk[2 * i + 1] = (float)(12000.0 / 32767.0 * x);
            }
            snd_pcm_sframes_t w = fl ? snd_pcm_writei(pcm, fchunk, n) : snd_pcm_writei(pcm, chunk, n);
            if (w == -EAGAIN) w = 0;
            if (w < 0) { snd_pcm_recover(pcm, w, 1); w = 0; }
            done += w;
            phase -= n - w;
        }
        struct timespec ts = {0, 10 * 1000 * 1000};
        nanosleep(&ts, NULL);
    }
    snd_pcm_sframes_t delay = 0;
    snd_pcm_delay(pcm, &delay);
    double el = now() - t0;
    printf("alsa-test: wrote %ld frames (%.2f s of audio) in %.2f s, delay %ld frames, xruns %ld\n", done, (double)done / rate, el, (long)delay, xruns);
    snd_pcm_close(pcm);
    return 0;
}
