#!/usr/bin/env python3
"""Deterministic APK signing key of the SHC port (docs/SIGNING.md). Never prints the seed or the private key.

The private key is derived from the GitHub Actions secret SHC_SIGNING_SEED:

    okm = PBKDF2-HMAC-SHA256(password = seed as UTF-8 bytes,
                             salt     = b"io.github.saefalasalynbtel198.shcport/android-signing/v1",
                             iterations = 600000, length = 40 bytes)
    d   = (int.from_bytes(okm, "big") mod (n - 1)) + 1        n = order of the P-256 curve
    key = ECDSA P-256 (secp256r1) private key with scalar d

The certificate is created once ("bootstrap": self-signed X.509, fields below, SHA256withECDSA with deterministic
RFC 6979 signatures), committed as signing/shc-signing-cert.pem and pinned by its SHA-256 in
signing/certificate-sha256.txt. Every build re-derives the key, checks that it belongs to the committed certificate
and only then writes a temporary PKCS12 keystore for Gradle.

  shc_signing.py bootstrap <out.pem>     first time only: write the certificate (public) for committing
  shc_signing.py keystore <dir>          derive, check against the committed certificate, write <dir>/shc.p12 and
                                         append SHC_SIGNING_KEYSTORE_FILE / SHC_SIGNING_KEYSTORE_PASSWORD to $GITHUB_ENV
"""
import datetime
import hashlib
import os
import secrets
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

SALT = b"io.github.saefalasalynbtel198.shcport/android-signing/v1"
ITERATIONS = 600_000
OKM_BYTES = 40   # 256 + 64 bits, reduced modulo n - 1 (negligible bias)
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
MIN_SEED = 32
ALIAS = "shc"

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
CERT_FILE = os.path.join(REPO, "signing", "shc-signing-cert.pem")
PIN_FILE = os.path.join(REPO, "signing", "certificate-sha256.txt")


def fail(msg: str) -> None:
    print(f"::error::{msg}")
    sys.exit(1)


def seed_bytes() -> bytes:
    seed = os.environ.get("SHC_SIGNING_SEED", "")
    if not seed:
        fail("The GitHub Actions secret SHC_SIGNING_SEED is not available (not created yet, or a pull request from a "
             "fork). No APK is signed with any other key. See docs/SIGNING.md.")
    if seed != seed.strip():
        fail("SHC_SIGNING_SEED starts or ends with spaces or line breaks (copy-paste error?). Store the password exactly.")
    if len(seed) < MIN_SEED:
        fail(f"SHC_SIGNING_SEED is shorter than {MIN_SEED} characters. Use a long random password from a password manager.")
    return seed.encode("utf-8")


def derive_private_key() -> ec.EllipticCurvePrivateKey:
    okm = hashlib.pbkdf2_hmac("sha256", seed_bytes(), SALT, ITERATIONS, dklen=OKM_BYTES)
    d = int.from_bytes(okm, "big") % (N - 1) + 1
    return ec.derive_private_key(d, ec.SECP256R1())


def make_certificate(key: ec.EllipticCurvePrivateKey) -> x509.Certificate:
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "SHC Port"),
                      x509.NameAttribute(NameOID.ORGANIZATION_NAME, "saefalasalynbtel198")])
    utc = datetime.timezone.utc
    return (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(1)
            .not_valid_before(datetime.datetime(2026, 1, 1, tzinfo=utc))
            .not_valid_after(datetime.datetime(2055, 12, 31, tzinfo=utc))
            .sign(key, hashes.SHA256(), ecdsa_deterministic=True))


def cert_sha256(cert: x509.Certificate) -> str:
    return hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()


def pinned() -> str:
    if not os.path.isfile(PIN_FILE):
        return ""
    lines = [l.strip() for l in open(PIN_FILE, encoding="ascii") if l.strip() and not l.lstrip().startswith("#")]
    return lines[0].replace(":", "").lower() if lines else ""


def bootstrap(out: str) -> None:
    if os.path.isfile(CERT_FILE):
        fail("signing/shc-signing-cert.pem already exists; bootstrap is only for the very first certificate.")
    cert = make_certificate(derive_private_key())
    pem = cert.public_bytes(serialization.Encoding.PEM).decode("ascii")
    with open(out, "w", encoding="ascii") as f:
        f.write(pem)
    print("Public certificate (not a secret), to commit as signing/shc-signing-cert.pem:")
    print(pem.strip())
    print(f"certificate SHA-256: {cert_sha256(cert)}")


def keystore(directory: str) -> None:
    if not os.path.isfile(CERT_FILE):
        fail("No committed signing certificate (signing/shc-signing-cert.pem): run the bootstrap first (docs/SIGNING.md).")
    cert = x509.load_pem_x509_certificate(open(CERT_FILE, "rb").read())
    want = pinned()
    got = cert_sha256(cert)
    if len(want) != 64 or got != want:
        fail(f"signing/shc-signing-cert.pem has SHA-256 {got}, but signing/certificate-sha256.txt pins '{want}'.")
    key = derive_private_key()
    if key.public_key().public_numbers() != cert.public_key().public_numbers():
        fail("SHC_SIGNING_SEED does not produce the key of the committed certificate (wrong or changed secret). "
             "Refusing to sign: an APK with another key could not update the installed app.")
    password = secrets.token_urlsafe(32)   # protects only the temporary file; new each run
    os.makedirs(directory, mode=0o700, exist_ok=True)
    path = os.path.join(directory, "shc.p12")
    data = pkcs12.serialize_key_and_certificates(ALIAS.encode(), key, cert, None,
                                                  serialization.BestAvailableEncryption(password.encode()))
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    print(f"::add-mask::{password}")
    env = os.environ.get("GITHUB_ENV")
    if not env:
        fail("GITHUB_ENV not set")
    with open(env, "a", encoding="ascii") as f:
        f.write(f"SHC_SIGNING_KEYSTORE_FILE={path}\nSHC_SIGNING_KEYSTORE_PASSWORD={password}\n")
    print(f"Signing key derived; it matches the committed certificate {got}. Temporary keystore written (mode 0600).")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "bootstrap":
        bootstrap(sys.argv[2])
    elif len(sys.argv) == 3 and sys.argv[1] == "keystore":
        keystore(sys.argv[2])
    else:
        print(__doc__)
        sys.exit(2)
