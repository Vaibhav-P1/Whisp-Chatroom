# 🗨️ WhispChatRoom - Real-Time Chat via Joinable Rooms

## 🚀 Project Overview
WhispChatRoom is a real-time chat application built using **Firebase Realtime Database / Firestore**, where users can create or join chat rooms using a unique code. Each room persists until explicitly closed or deleted.  
Users can chat anonymously with unique usernames per room.
---
## Features

- Create Room
  - Enter a username
  - Use saved username or enter a new one
  - Generate a unique room code
  - Check username uniqueness in the room

- Join Room
  - Enter room code
  - Enter username
  - Check username uniqueness in the room
  - Load chat interface if valid

- Real-Time Chat
  - Send and receive messages instantly
  - Messages store username, timestamp, and text

- Room Owner Privileges
  - Close Room (disables further messages)
  - Delete Room (removes all data)

- Optional Temporary Room
  - Auto-delete when creator exits or closes room

---

## Tech Stack

- Firebase Realtime Database / Firestore  
- Firebase Anonymous Auth / Local Device ID  
- ViewModel + LiveData  
- SharedPreferences / DataStore  
- RecyclerView, ConstraintLayout, Material Design  

---
### 📱 Screenshots

<img width="442" height="965" alt="image" src="https://github.com/user-attachments/assets/10e5c427-71c3-4011-91ff-a2fd15a768aa" />
<img width="447" height="973" alt="image" src="https://github.com/user-attachments/assets/c4ed7b04-4249-4a6e-b850-d25cf7ddd5f2" />

<img width="435" height="934" alt="image" src="https://github.com/user-attachments/assets/9fe04385-79e3-49ff-b8b4-7cb9b8ae595f" />
<img width="429" height="944" alt="image" src="https://github.com/user-attachments/assets/6f1f2503-a247-44ed-bab9-b6f8b9137e22" />

<img width="443" height="971" alt="image" src="https://github.com/user-attachments/assets/6f8e1472-31ba-4796-890f-03c920be964e" />

<img width="1728" height="686" alt="image" src="https://github.com/user-attachments/assets/177ce4d7-afe4-4d31-aaa7-272de1fc85e2" />

<img width="1727" height="787" alt="image" src="https://github.com/user-attachments/assets/0c3f1717-c6e8-4d28-b668-075fe2261da8" />


---


###⚠️ Important Setup Instruction

To run this project, you must add your own Firebase config file.

1. Go to https://console.firebase.google.com/
2. Create a project and register an Android app.
3. Download the `google-services.json` file.
4. Place it inside the `app/` directory.

This file is intentionally not included in the repo for security reasons.
