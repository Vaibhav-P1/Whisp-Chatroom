# 🗨️ WhispChatRoom - Real-Time Chat via Joinable Rooms

[![CI](https://github.com/Vaibhav-P1/Whisp-Chatroom/actions/workflows/ci.yml/badge.svg)](https://github.com/Vaibhav-P1/Whisp-Chatroom/actions/workflows/ci.yml)

## Project Overview
WhispChatRoom is an Android chat app where signed-in users create or join chat rooms with a unique 6-character room code. Messages sync in real time through **Cloud Firestore**. Room owners can close or delete a room, and a room can be marked temporary so it is removed when the creator leaves.

## Features

- **Account**
  - Email/password sign up and login with Firebase Authentication
  - First and last name stored in a Firestore user profile

- **Create Room**
  - Choose a username (optionally saved as your default)
  - Generates a unique room code, shareable via copy or the share sheet
  - Optional temporary room

- **Join Room**
  - Enter a room code and username
  - Username must be unique within the room

- **Real-Time Chat**
  - Messages appear instantly for everyone in the room
  - Each message stores the sender, text and timestamp

- **Room Owner Privileges**
  - Close Room (stops further messages)
  - Delete Room (removes the room and its messages)

## Tech Stack

- Kotlin, Jetpack Compose (Material 3), Navigation Compose
- MVVM with ViewModel + LiveData
- Firebase Authentication and Cloud Firestore
- SharedPreferences for the saved username
- Gradle Kotlin DSL with a version catalog (`gradle/libs.versions.toml`)

## Build Requirements

- Android Studio with **JDK 21** set as the Gradle JDK (Settings > Build Tools > Gradle)
- Android SDK 35
- Gradle 8.9 and Android Gradle Plugin 8.7.3 (via the wrapper and version catalog)

## Screenshots

<img width="442" height="965" alt="image" src="https://github.com/user-attachments/assets/10e5c427-71c3-4011-91ff-a2fd15a768aa" />
<img width="447" height="973" alt="image" src="https://github.com/user-attachments/assets/c4ed7b04-4249-4a6e-b850-d25cf7ddd5f2" />

<img width="435" height="934" alt="image" src="https://github.com/user-attachments/assets/9fe04385-79e3-49ff-b8b4-7cb9b8ae595f" />
<img width="429" height="944" alt="image" src="https://github.com/user-attachments/assets/6f1f2503-a247-44ed-bab9-b6f8b9137e22" />

<img width="443" height="971" alt="image" src="https://github.com/user-attachments/assets/6f8e1472-31ba-4796-890f-03c920be964e" />

<img width="1728" height="686" alt="image" src="https://github.com/user-attachments/assets/177ce4d7-afe4-4d31-aaa7-272de1fc85e2" />

<img width="1727" height="787" alt="image" src="https://github.com/user-attachments/assets/0c3f1717-c6e8-4d28-b668-075fe2261da8" />


## ⚠️ Setup

To run this project you must add your own Firebase config file.

1. Go to https://console.firebase.google.com/
2. Create a project and register an Android app with package name `com.example.wishpchatroom`.
3. Enable **Email/Password** sign-in under Authentication and create a **Firestore** database.
4. Download `google-services.json` and place it inside the `app/` directory.

This file is intentionally not included in the repo for security reasons.
