"""Install checksum-pinned official SDK tools before any signing secret is read."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile

FILES = {
    'build-tools_r36_linux.zip': '5d9ac77fb6ff43d9da518a337b4fcf8f9097113df531d99ccefe80ef7ce8250b',
    'commandlinetools-linux-16111833_latest.zip': '0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8',
}


def extract(name, destination):
    with tempfile.TemporaryFile() as output:
        digest = hashlib.sha256()
        with urllib.request.urlopen('https://dl.google.com/android/repository/' + name, timeout=60) as response:
            for block in iter(lambda: response.read(1024 * 1024), b''):
                digest.update(block)
                output.write(block)
        if digest.hexdigest() != FILES[name]:
            raise RuntimeError('Official SDK archive checksum mismatch')
        output.seek(0)
        with zipfile.ZipFile(output) as archive:
            for entry in archive.infolist():
                path = Path(entry.filename)
                if path.is_absolute() or '..' in path.parts:
                    raise RuntimeError('Unsafe SDK archive path')
                archive.extract(entry, destination)
                target = destination / path
                if target.is_file():
                    target.chmod(0o755 if entry.external_attr >> 16 & 0o111 else 0o644)


def main():
    home = Path(os.environ['ANDROID_HOME'])
    home.mkdir(parents=True, exist_ok=True)
    if os.environ.get('SIGNING_SDK_ONLY') == 'true':
        extract('build-tools_r36_linux.zip', home)
        roots = list(home.iterdir())
        if len(roots) != 1 or not roots[0].is_dir():
            raise RuntimeError('Unexpected official build-tools archive layout')
        (home / 'build-tools').mkdir()
        roots[0].rename(home / 'build-tools/36.0.0')
        return
    tools = home / 'cmdline-tools'
    manager = tools / 'latest/bin/sdkmanager'
    if not manager.exists():
        tools.mkdir(exist_ok=True)
        extract('commandlinetools-linux-16111833_latest.zip', tools)
        (tools / 'cmdline-tools').rename(tools / 'latest')
    subprocess.run([str(manager), '--licenses'], input=b'y\n' * 200,
                   stdout=subprocess.DEVNULL, check=True, timeout=180)
    subprocess.run([str(manager), 'platforms;android-36', 'build-tools;36.0.0', 'platform-tools'],
                   stdout=subprocess.DEVNULL, check=True, timeout=300)


if __name__ == '__main__':
    main()
