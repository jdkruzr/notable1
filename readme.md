hey <!-- markdownlint-configure-file {
  "MD013": {"code_blocks": false, "tables": false},
  "MD033": false,
  "MD041": false
} -->

<div align="center">

[![License][license-shield]][license-url]
[![Total Downloads][downloads-shield]][downloads-url]
[![Discord][discord-shield]][discord-url]

![Notable App][logo]

# Notable (Fork)

A maintained and customized fork of the archived [olup/notable](https://github.com/olup/notable) project.

[![🐛 Report Bug][bug-shield]][bug-url]
[![Download Latest][download-shield]][download-url]
[![💡 Request Feature][feature-shield]][feature-url]

<a href="https://github.com/sponsors/ethran">
  <img src="https://img.shields.io/badge/Sponsor_on-GitHub-%23ea4aaa?logo=githubsponsors&style=for-the-badge" alt="Sponsor on GitHub">
</a>

<a href="https://ko-fi.com/rethran" target="_blank">
  <img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support me on Ko-fi">
</a>

</div>

---
<details>
  <summary>Table of Contents</summary>

- [About This Fork](#about-this-fork)  
- [Features](#features)  
- [Download](#download)  
- [Gestures](#gestures)  
- [Supported Devices](#supported-devices)  
- [Roadmap](#roadmap)  
- [Screenshots](#screenshots)  
- [Contribute](#contribute)  

</details>


---

## About This Fork
This fork is maintained by **Ethran** as a continuation and personal enhancement of the original Notable app. Development is semi-active and tailored toward personal utility while welcoming community suggestions.

### What's New?
- Regular updates and experimental features
- Improved usability and speed
- Custom features suited for e-ink devices and note-taking

> ⚠️ Note: Features may reflect personal preferences.

---

## Features
* ⚡ **Fast Page Turn with Caching:** Notable leverages caching techniques to ensure smooth and swift page transitions, allowing you to navigate through your notes seamlessly. (next and previous pages are cached)
* ↕️ **Infinite Vertical Scroll:** Enjoy a virtually endless canvas for your notes. Scroll vertically without limitations. You can even enjoy smooth scrolling.
* 📝 **Quick Pages:** Quickly create a new page using the Quick Pages feature.
* 📒 **Notebooks:** Keep related notes together and easily switch between different noteboo︂︂ks based on your needs.
* 📁 **Folders:** Create folders to organize your notes.
* 🤏 **Editors' Mode Gestures:** [Intuitive gesture controls](#gestures) to enhance the editing experience.
* 🌅 **Images:** Add, move, scale, and remove images.
* ︂︂᠋︁➤  **Selection export:** share selected text.

## Download
**Download the latest stable version of the [Notable app here.](https://github.com/Ethran/notable/releases/latest)**

Alternatively, get the latest build from main from the ["next" release](https://github.com/Ethran/notable/releases/next)

Open up the '**Assets**' from the release, and select the `.apk` file.

<details><summary title="Click to show/hide details">❓ Where can I see alternative/older releases?</summary><br/>
You can go to original olup <a href="https://github.com/olup/notable/tags" target="_blank">'Releases'</a> and download alternative versions of the Notable app.
</details>

<details><summary title="Click to show/hide details">❓ What is a 'next' release?</summary><br/>
The 'next' release is a pre-release, and will contain features implemented but not yet released as part of a version - and sometimes experiments that could very well not be part a release.
</details>

---

## Gestures
Notable features intuitive gestures controls within Editor's Mode, to optimize the editing experience:
#### ☝️ 1 Finger
* **Swipe up or down**: Scroll the page.
* **Swipe left or right:** Change to the previous/next page (only available in notebooks).
* **Double tap:** Undo
* **Hold and drag:** select text and images
#### ✌️ 2 Fingers
* **Swipe left or right:** Show or hide the toolbar.
* **Single tap:** Switch between writing modes and eraser modes.
* **Pinch**: Zoom in and out.

#### 🔲 Selection
* **Drag:** Move the selected writing around.
* **Double tap:** Copy the selected writing.

## Supported Devices

The following table lists devices confirmed by users to be compatible with specific versions of Notable.  
This does not imply any commitment from the developers.
| Device Name                                                                           | v0.0.10 | v0.0.11dev |   v0.0.14+     |        |        |
|---------------------------------------------------------------------------------------|---------|------------|--------|--------|--------|
| [ONYX Boox Go 10.3](https://onyxboox.com/boox_go103)                                  | ✔       | ?          |    ✔    |        |        |
| [Onyx Boox Note Air 4 C](https://onyxboox.pl/en/ebook-readers/onyx-boox-note-air-4-c) | ✘       | ✔          |    ✔    |        |        |
| [Onyx Boox Note Air 3 C](https://onyxboox.pl/en/ebook-readers/onyx-boox-note-air-3-c) | ✘       | ✔          |    ✔    |        |        |
| [Onyx Boox Note Max](https://shop.boox.com/products/notemax)                          | ✘       | ✔          |    ✔    |        |        |
| [Boox Note 3](https://onyxboox.pl/en/ebook-readers/onyx-boox-note-3)    | ✔       |  ✘   https://github.com/Ethran/notable/issues/24        |    ✔    |        |        |

Feel free to add your device if tested successfully!

## Roadmap

Features I’d like to implement in the future (some might take a while — or a long while):

- [ ] Bookmarks support, tags, and internal links — [Issue #52](https://github.com/Ethran/notable/issues/52)  
  - [ ] Export links to PDF  

- [x] Better notebook covers, provide default styles of title page

- [ ] PDF annotation
  - [x] basic support
  - [ ] Show annotation from other programs
  - [ ] Allow to save annotations to original pdf file

- [ ] Figure and text recognition — [Issue #44](https://github.com/Ethran/notable/issues/44)  
  - [ ] Searchable notes  
  - [ ] Automatic creation of tag descriptions  
  - [ ] Shape recognition  

- [ ] Moving page horizontally -- it would be nice to write on margins.

- [ ] Better selection tools  
  - [ ] Stroke editing: color, size, etc.  
  - [ ] Rotate  
  - [ ] Flip selection  
  - [ ] Auto-scroll when dragging selection to screen edges  
  - [ ] Easier selection movement (e.g. dragging to scroll page)

- [ ] More dynamic page and notebook movement. Currently, pages can only be moved left/right — add drag-and-drop support

- [ ] Custom drawing tools, might not be possible.


---

## Screenshots

<div style="display: flex; flex-wrap: wrap; gap: 10px;">
  <img src="https://github.com/user-attachments/assets/1025087c-5765-425f-ab1d-a82908b56d5e" alt="screenshot-1" width="200"/>
  <img src="https://github.com/user-attachments/assets/1d6ba940-828c-47fe-a5f5-3128e06382dd" alt="screenshot-2" width="200"/>
  <img src="https://github.com/user-attachments/assets/bfa9ee13-3f89-4ae3-8349-1a25e2efd275" alt="screenshot-3" width="200"/>
  <img src="https://github.com/user-attachments/assets/9f3e7012-69e4-4125-bf69-509b52e1ebaf" alt="screenshot-5" width="200"/>
  <img src="https://github.com/user-attachments/assets/24c8c750-eb8e-4f01-ac62-6a9f8e5f9e4f" alt="screenshot-6" width="200"/>
  <img src="https://github.com/user-attachments/assets/4cdb0e74-bfce-4dba-bc21-886a5834401e" alt="screenshot-7" width="200"/>
</div>

---

## Contribute

Notable is an open-source project, and contributions are welcome. If you'd like to get started, please refer to [GitHub's contributing guide](https://docs.github.com/en/get-started/quickstart/contributing-to-projects).

### Development Notes

- Edit the `DEBUG_STORE_FILE` in `/app/gradle.properties` to point to your local keystore file. This is typically located in the `.android` directory.
- To debug on a BOOX device, enable developer mode. You can follow [this guide](https://imgur.com/a/i1kb2UQ).

Feel free to open issues or submit pull requests. I appreciate your help!

---

<!-- MARKDOWN LINKS -->
[logo]: https://github.com/Ethran/notable/blob/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true "Notable Logo"
[contributors-shield]: https://img.shields.io/github/contributors/Ethran/notable.svg?style=for-the-badge
[contributors-url]: https://github.com/Ethran/notable/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/Ethran/notable.svg?style=for-the-badge
[forks-url]: https://github.com/Ethran/notable/network/members
[stars-shield]: https://img.shields.io/github/stars/Ethran/notable.svg?style=for-the-badge
[stars-url]: https://github.com/Ethran/notable/stargazers
[issues-shield]: https://img.shields.io/github/issues/Ethran/notable.svg?style=for-the-badge
[issues-url]: https://github.com/Ethran/notable/issues
[license-shield]: https://img.shields.io/github/license/Ethran/notable.svg?style=for-the-badge

[license-url]: https://github.com/Ethran/notable/blob/master/LICENSE.txt
[download-shield]: https://img.shields.io/github/v/release/Ethran/notable?style=for-the-badge&label=⬇️%20Download
[download-url]: https://github.com/Ethran/notable/releases/latest
[downloads-shield]: https://img.shields.io/github/downloads/Ethran/notable/total?style=for-the-badge&color=47c219&logo=cloud-download
[downloads-url]: https://github.com/Ethran/notable/releases/latest

[discord-shield]: https://img.shields.io/badge/Discord-Join%20Chat-7289DA?style=for-the-badge&logo=discord
[discord-url]: https://discord.gg/rvNHgaDmN2
[kofi-shield]: https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ko--fi-ff5f5f?style=for-the-badge&logo=ko-fi&logoColor=white
[kofi-url]: https://ko-fi.com/rethran

[sponsor-shield]: https://img.shields.io/badge/Sponsor-GitHub-%23ea4aaa?style=for-the-badge&logo=githubsponsors&logoColor=white
[sponsor-url]: https://github.com/sponsors/rethran

[docs-url]: https://github.com/Ethran/notable
[bug-url]: https://github.com/Ethran/notable/issues/new?labels=bug&template=bug-report---.md
[feature-url]: https://github.com/Ethran/notable/issues/new?labels=enhancement&template=feature-request---.md
[bug-shield]: https://img.shields.io/badge/🐛%20Report%20Bug-red?style=for-the-badge
[feature-shield]: https://img.shields.io/badge/💡%20Request%20Feature-blueviolet?style=for-the-badge
