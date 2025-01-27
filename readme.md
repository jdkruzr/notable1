<!-- markdownlint-configure-file {
  "MD013": {
    "code_blocks": false,
    "tables": false
  },
  "MD033": false,
  "MD041": false
} -->

<div align="center">

![Notable App](https://github.com/olup/notable/blob/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true "Notable Logo")
# Notable
  
  
<a href="https://github.com/olup/notable/releases/latest">
  <img src="https://img.shields.io/badge/-download%20here-informational" alt="Download here">
</a><br/>
  
  
<a href="https://github.com/olup/notable/releases/latest">
  <img src="https://img.shields.io/github/downloads/olup/notable/total?color=47c219" alt="Downloads">
</a>
<a href="https://discord.com/invite/X3tHWZHUQg">
  <img src="https://img.shields.io/badge/discord-7289da.svg" alt="Discord">
</a>
  
[Features](#features) •
[Download](#download) •
[Gestures](#gestures) •
[Contribute](#contribute)
  
</div>


Notable Plus is a **custom note-taking app designed specifically for BOOX e-ink devices.** built on [the original noteable source code] (https://github.com/olup/notable) and [the original Notable app](https://github.com/olup/notable/releases/latest).


## Features
* ⚡ **Fast Page Turn with Caching:** Notable leverages caching techniques to ensure smooth and swift page transitions, allowing you to navigate through your notes seamlessly.
* ↕️ **Infinite Vertical Scroll:** Enjoy a virtually endless canvas for your notes. Scroll vertically without limitations.
* 📝 **Quick Pages:** Quickly create a new page using the Quick Pages feature.
* 📒 **Notebooks:** Keep related notes together and easily switch between different notebooks based on your needs.
* 📁 **Folders:** Create folders to organize your notes.
* 🤖 **AI Integration:** Chat with your locally hosted LLMs directly on your e-ink device for enhanced note-taking and analysis.
* 📅 **Calendar Integration:** Effortlessly create appointments by writing down dates and times in your notes.
* 🔄 **Obsidian Integration:** Capture spontaneous notes and let AI automatically organize them within your Obsidian vault.
* 🤏 **Editors' Mode Gestures:** [Intuitive gesture controls](#gestures) to enhance the editing experience.

## Download
**Download the latest stable version of the [Notable app here.](https://github.com/olup/notable/releases/latest)**

Alternatively, get the latest build from main from the ["next" release](https://github.com/olup/notable/releases/next)

Open up the '**Assets**' from the release, and select the `.apk` file.

<details><summary title="Click to show/hide details">❓ Where can I see alternative/older releases?</summary><br/>
Select the projects <a href="https://github.com/olup/notable/tags" target="_blank">'Releases'</a> and download alternative versions of the Notable app.
</details>

<details><summary title="Click to show/hide details">❓ What is a 'next' release?</summary><br/>
The 'next' release is a pre-release, and will contain features imlemented but not yet released as part of a version - and sometimes experiments that could very well not be part a release.
</details>

## Gestures
Notable features intuitive gestures controls within Editor's Mode, to optimize the editing experience:
#### ☝️ 1 Finger
* **Swipe up or down**: Scroll the page.
* **Swipe left or right:** Change to the previous/next page (only available in notebooks).
* **Double tap:** Show or hide the toolbar.
* **Double tap bottom part of the screen:** Show quick navigation.

#### ✌️ 2 Fingers
* **Swipe left or right:** Undo/redo your changes.
* **Single tap:** Switch between writing modes and eraser modes.

#### 🔲 Selection
* **Drag:** Move the selected writing around.
* **Double tap:** Copy the selected writing.

## Contribute
Notable is an open-source project and welcomes contributions from the community. 
To start working with the project, see [the guide on how to start contributing](https://docs.github.com/en/get-started/quickstart/contributing-to-projects) to the project. 

***Important:*** Be sure to edit the `DEBUG_STORE_FILE` variable in the `/app/gradle.properties` file to the keystore on your own device. This is likely stored in the `.android` directory on your device.

***Important:*** To use your BOOX device for debugging, an application will be required to enable developer mode on your BOOX device. [See a short guide here.](https://imgur.com/a/i1kb2UQ)
