# Third-party components

| Component | Where it is used | Licence | Source |
|---|---|---|---|
| proot (Termux build 5.1.107.102) | `libproot.so`, `libproot-loader.so` | GPL-2.0 | https://github.com/termux/proot |
| talloc 2.5.0 | `libtalloc.so` | LGPL-3.0 | https://talloc.samba.org |
| libandroid-shmem 0.7 | `libandroid-shmem.so` | BSD-3-Clause | https://github.com/termux/libandroid-shmem |
| Termux terminal-emulator and terminal-view v0.118.1 | terminal screen | Apache-2.0 | https://github.com/termux/termux-app |
| Apache Commons Compress | unpacking Ubuntu | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |
| Ubuntu Base 24.04.3 (downloaded at runtime, not bundled) | Linux system | various (Ubuntu) | https://cdimage.ubuntu.com/ubuntu-base/ |
| Hermes Agent (installed at runtime, not bundled) | the agent | see its repository | https://github.com/NousResearch/hermes-agent |

The proot binaries are taken unmodified from Termux's signed package repository (see
`tools/fetch-proot.sh` for exact versions and SHA-256 sums). This is a private test build. Before
any public release, the GPL and LGPL terms (source availability, notices in the app) must be met.
