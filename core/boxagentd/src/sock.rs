//! Abstract-namespace Unix socket binding via libc.
//!
//! Android abstract sockets live in the kernel namespace (no filesystem
//! node), addressed by a leading NUL in `sun_path`.

use anyhow::{Context, Result};
use std::io;
use std::mem;
use std::os::unix::io::FromRawFd;
use tokio::net::UnixListener;

pub fn bind_abstract(name: &str) -> Result<UnixListener> {
    unsafe {
        let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0);
        if fd < 0 {
            return Err(io::Error::last_os_error()).context("socket()");
        }

        let mut addr: libc::sockaddr_un = mem::zeroed();
        addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
        let name_bytes = name.as_bytes();
        if name_bytes.len() + 1 > addr.sun_path.len() {
            libc::close(fd);
            anyhow::bail!("socket name too long: {name}");
        }
        // sun_path[0] = 0 marks the address as abstract.
        addr.sun_path[0] = 0;
        std::ptr::copy_nonoverlapping(
            name_bytes.as_ptr() as *const libc::c_char,
            addr.sun_path.as_mut_ptr().add(1),
            name_bytes.len(),
        );
        let addr_len = (mem::size_of::<libc::sa_family_t>() + 1 + name_bytes.len())
            as libc::socklen_t;

        if libc::bind(fd, &addr as *const _ as *const libc::sockaddr, addr_len) < 0 {
            let e = io::Error::last_os_error();
            libc::close(fd);
            return Err(e).context("bind()");
        }
        // World-accessible within the uid boundary is fine: peers must still
        // present the one-time token, and the abstract name is randomized.
        if libc::listen(fd, 16) < 0 {
            let e = io::Error::last_os_error();
            libc::close(fd);
            return Err(e).context("listen()");
        }

        // Non-blocking before handing to tokio.
        let flags = libc::fcntl(fd, libc::F_GETFL);
        libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK);

        let std_listener = std::os::unix::net::UnixListener::from_raw_fd(fd);
        UnixListener::from_std(std_listener).context("from_std")
    }
}
