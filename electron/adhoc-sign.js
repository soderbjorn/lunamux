/**
 * electron-builder afterPack hook for local, unsigned macOS builds: ad-hoc signs the packed
 * Lunamux.app so macOS knows it as its own app instead of as stock Electron.
 *
 * Why it exists: with signing turned off, electron-builder leaves the main executable with the
 * linker signature Electron ships with, whose identifier is "Electron". macOS's local network
 * privacy then cannot tie the running app to its bundle: it looks the executable up by its
 * Mach-O UUID, which every unsigned app built on the same Electron version shares with the
 * stock Electron.app in node_modules, and so it applies Electron's permission entry rather than
 * Lunamux's. The toggle for Lunamux in System Settings has no effect, and every command run in
 * a Lunamux terminal (ssh, nc, curl to a LAN address) fails with "No route to host".
 * An ad-hoc signature over the whole bundle gives the main executable the bundle's identifier,
 * se.soderbjorn.termtastic, which macOS matches to Lunamux's own entry.
 *
 * Only scripts/build-electron-no-notarize.sh passes this hook. The release build
 * (scripts/build-release-electron.sh) signs with the Developer ID certificate, which already
 * sets the identifier and the team, so it must not run here.
 */
const { execFileSync } = require("child_process");
const path = require("path");

/**
 * Ad-hoc signs the packed app, then verifies the signature.
 *
 * electron-builder calls it after packing each platform's app and before signing and building
 * the targets (the .dmg), so the .dmg and the .app under dist/mac* both carry the signature.
 * Hardened runtime is left off, as in the unsigned build before this hook: the bundled JRE and
 * the server jar's native libraries need no entitlements that way.
 *
 * @param {object} context electron-builder's AfterPackContext.
 * @param {string} context.electronPlatformName "darwin" on macOS; other platforms are skipped.
 * @param {string} context.appOutDir The directory holding the packed app.
 * @param {object} context.packager The platform packager; its appInfo.productFilename names the .app.
 * @returns {Promise<void>} Resolves once the app is signed and verified; rejects if codesign fails.
 * @see ../scripts/build-electron-no-notarize.sh
 */
module.exports = async function adhocSign(context) {
    if (context.electronPlatformName !== "darwin") return;
    const app = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`);
    console.log(`==> Ad-hoc signing ${app}`);
    execFileSync("codesign", ["--force", "--deep", "--sign", "-", app], { stdio: "inherit" });
    execFileSync("codesign", ["--verify", "--deep", "--strict", app], { stdio: "inherit" });
};
