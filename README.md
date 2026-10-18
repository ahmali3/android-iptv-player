# IPTV Player

A fast, simple player for live TV from your IPTV provider. It works with **Xtream Codes** logins and **M3U** playlist links, and shows a TV guide. It has no ads, no account and no channels of its own: you bring your own subscription.

All downloads are on the **[latest release](../../releases/latest)** page, under **Assets**. Pick the file for your device below.

| Device | File to download |
|---|---|
| Android phone or tablet | `IPTV-Player-1.0.xx.apk` |
| Nvidia Shield, Chromecast with Google TV, Google TV / Android TV (64-bit) | `IPTV-Player-1.0.xx.apk` |
| Fire TV Stick, Mi Box, and other older or cheaper TV boxes | `IPTV-Player-1.0.xx-32bit.apk` |
| LG TV (webOS 5 or newer, 2020+) | `IPTV-Player-LG-webOS-1.0.xx.ipk` |
| Roku | `IPTV-Player-Roku-1.0.xx.zip` |

Not sure which Android TV file? Try the normal one first. If it says *"App not installed"*, use the 32-bit one.

---

## Android phone or tablet

Needs Android 8.0 or newer.

1. On your phone, open the [latest release](../../releases/latest) and tap `IPTV-Player-1.0.xx.apk` to download it.
2. Open the downloaded file.
3. If Android asks, tap **Settings** and turn on **Allow from this source**, then go back and tap **Install**.
4. If Play Protect warns you, tap **More details → Install anyway**. (It warns because the app isn't from the Play Store.)

To update, install the newer APK the same way. Your playlists and favorites are kept.

## Android TV, Google TV, Nvidia Shield, Fire TV

1. On the TV, install the free **Downloader** app (by AFTVnews) from the Play Store or Amazon Appstore.
2. Allow it to install apps:
   - **Android TV / Google TV / Shield:** Settings → Apps → Security & restrictions → Unknown sources → turn on **Downloader**.
   - **Fire TV:** Settings → My Fire TV → Developer options → Install unknown apps → turn on **Downloader**. (No Developer options? Go to My Fire TV → About and click the device name 7 times.)
3. Open Downloader and type the address of the APK file. To get it, open the [latest release](../../releases/latest) on your phone, long-press the right APK file and copy the link.
4. Install it, then open **IPTV Player** from the TV's apps row.

Use the remote: **up/down** change channel while watching, **OK** shows the controls, **left** opens the channel list, **Back** goes back.

## LG TV (webOS)

LG doesn't allow installing apps outside its store, except in **Developer Mode**. It's free.

1. On your computer or phone, create a free account at [webostv.developer.lge.com](https://webostv.developer.lge.com).
2. On the TV, install the **Developer Mode** app from the LG Content Store. Open it, sign in with that account, and turn **Dev Mode Status** on. The TV restarts.
3. Open Developer Mode again and turn **Key Server** on. Note the TV's IP address shown there.
4. On a computer on the same Wi-Fi, install the [webOS Dev Manager](https://github.com/webosbrew/dev-manager-desktop/releases) app (Windows, Mac or Linux).
5. In Dev Manager, add your TV (use the IP address and the passphrase shown in the Developer Mode app).
6. Download `IPTV-Player-LG-webOS-1.0.xx.ipk` from the [latest release](../../releases/latest), then in Dev Manager go to **Apps → Install** and pick that file.
7. Open **IPTV Player** from the TV's home screen.

Developer Mode turns itself off after about 50 hours unless you renew it. Open the Developer Mode app and press **Extend** once in a while (Dev Manager can do this for you). If it expires, the app is removed and you install it again the same way.

*If your TV is rooted with Homebrew Channel, you can install the .ipk from there instead and skip the renewal.*

## Roku

1. On your Roku remote, press **Home 3×, Up 2×, Right, Left, Right, Left, Right**. The Developer Settings screen opens.
2. Note the IP address shown, choose **Enable installer and restart**, accept the agreement and set a password.
3. Download `IPTV-Player-Roku-1.0.xx.zip` from the [latest release](../../releases/latest) to a computer or phone on the same Wi-Fi. Don't unzip it.
4. In a web browser, go to `http://` followed by the Roku's IP address (for example `http://192.168.1.50`). Sign in with user **rokudev** and the password you set.
5. Click **Upload**, choose the zip file, and click **Install with zip**.
6. The app opens right away, and it stays on your Roku's home screen as **IPTV Player**.

Roku can only play channels your provider offers in HLS format. The app asks Xtream providers for HLS automatically. If a channel won't play, your provider may not offer it in that format.

---

## First steps (all devices)

Open the app and add a playlist:

- **Xtream Codes:** enter the server address your provider gave you (like `http://example.com:8080`), your username and your password.
- **M3U:** paste your playlist link. If the link contains a username and password, the app turns it into an Xtream login automatically.

You can add several playlists and switch between them.

## If something doesn't work

- **Login failed:** check the server address, including `http://` and the port number.
- **A channel won't start:** most subscriptions allow only one or two streams at once. Stop watching on your other devices.
- **No guide for a channel:** your provider's guide doesn't include it.

## Privacy

Your playlists and logins stay on your device. The app only talks to your provider's servers.

## Disclaimer

This app is a player only and includes no channels. Use it only with services you're allowed to watch.
