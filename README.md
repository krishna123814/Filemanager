# MyFiles (Android)

Apne VPS ka file manager. Server par kuch naya install nahi karna: app seedha tere **WebDAV (`/dav`)** se baat karti hai
(wahi jo FolderSync/FX me `u8cyy.aiccloud.online/dav/` ke naam se add hai).

## Kya kya hai
- Login (server URL + username + password) -> phone me yaad rehta hai.
- Folder browse: list ya grid, sort (naam natural order me, date, size), pull-to-refresh.
- 🖼 Images: thumbnails (disk cache), full-screen viewer, swipe, pinch-zoom, double-tap zoom.
- 🎬 Video: seedha server se stream (Range requests, seek tez), folder ka next/previous, resume (jahan chhoda wahin se).
- 🎵 Music: stream + background playback + notification controls, folder ka playlist.
- Long-press: Download (Downloads/MyFiles), Rename, Delete, Info.
- Menu (⋮): Naya folder, Upload, Sort, Video thumbnails on/off, Cache saaf, Update check, Logout.
- Auto-update: GitHub Release ki `MyFiles.apk` se, bina uninstall ke.

## Do storage + Copy / Move (naya)
- Login ke baad **Home**: 💽 **AIC Disk** (VPS ki `/data`, WebDAV `/dav/`) aur ☁️ **HF Bucket** (Hugging Face Storage Bucket, WebDAV `/hf/`). Dono ek hi login se. Server par HF band ho to HF card dhundhla dikhta hai aur wajah batata hai.
- Header me storage badge (💽 AIC / ☁️ HF): dabao to doosre storage me chale jao.
- Long-press = selection mode. Upar bar: ✕ band, ☑ sab chuno, 📋 Copy, ✂ Move, 🗑 Delete, ⋮ (ek item par: Download / Rename / Info).
- Copy / Move dabane par neeche **Paste bar** aati hai ("N item · Paste here · Cancel"). Folder ya storage badalne par bhi bani rehti hai. Destination folder me jao aur **Paste here** dabao.
- Transfer **server par** chalta hai (phone se bytes nahi guzarte): app band ho jaye to bhi chalta rehta hai. Progress dialog me "Background me chalne do" dabao, ya Home / ⋮ se **Transfers** screen kholo (list, progress, cancel).
- Naam pehle se ho to dialog: Skip / Rename / Overwrite.
- HF par Delete **permanent** hota hai (trash nahi), isliye alag saaf warning aati hai.
- HF par abhi: folder ka rename nahi (folder ko Move se doosre folder me bhej sakte ho); video thumbnails band.
- Server par `/hf/` aur `/xfer` chahiye (HF_FILEMANAGER_PLAN.md ke Step 1 + 2).

## Build (GitHub Actions)
1. Naya repo banao. `unzip.yml` ko `.github/workflows/unzip.yml` me paste karo.
2. Repo Secrets me 4 cheezein (Lion wali hi chalti hain): `LION_KEYSTORE_BASE64`, `LION_STORE_PASSWORD`, `LION_KEY_ALIAS`, `LION_KEY_PASSWORD`.
   Na hon to debug key se sign hogi (tab update ke liye uninstall karna padega).
3. `MyFiles.zip` repo ke root me upload karo -> Actions "Unzip and Build APK" chalegi -> Releases se `MyFiles.apk`.

## Dhyan rakhne wali baatein
- Upload ke liye nginx me `client_max_body_size` bada hona chahiye (default 1 MB hota hai), warna bade upload fail honge.
- Server par transcoding nahi hai: jo codec phone support kare wahi video chalegi (mp4/H.264 sabse safe; mkv/HEVC phone par depend).
- Password app ke private storage me save hota hai (Android sandbox).
- Folder ki listing server ke PROPFIND par chalti hai; hazaron files wale folder me thoda time lag sakta hai.
