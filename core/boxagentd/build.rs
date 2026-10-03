// Stamps the daemon binary with the app versionName so overwrite-installed
// upgrades can detect a stale running daemon. rerun-if-env-changed makes
// cargo rebuild when the version env moves between builds.
fn main() {
    println!("cargo:rerun-if-env-changed=BOXAGENT_APP_VERSION");
    let v = std::env::var("BOXAGENT_APP_VERSION").unwrap_or_else(|_| "dev".into());
    println!("cargo:rustc-env=BOXAGENT_APP_VERSION={v}");
}
