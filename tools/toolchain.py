"""Pinned Android tools with explicit SDK/JDK locations on Windows and Linux."""
import os
from pathlib import Path


class Toolchain:
    def __init__(self):
        self.sdk = Path(os.environ['ANDROID_HOME'])
        self.jdk = Path(os.environ['JAVA_HOME'])
        self.bt = self.sdk / 'build-tools/35.0.0'
        self.android = self.sdk / 'platforms/android-35/android.jar'
        self.suffix = '.exe' if os.name == 'nt' else ''

    def java(self, name):
        return self.jdk / 'bin' / (name + self.suffix)

    def binary(self, name):
        return self.bt / (name + self.suffix)

    def compiler(self):
        host = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
        return self.sdk / 'ndk/26.1.10909125/toolchains/llvm/prebuilt' / host / 'bin' / ('clang' + self.suffix)
