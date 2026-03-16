# StreamBridge – Android Server

StreamBridge is a secure real-time communication system that connects an Android phone with a Windows PC over a local network (LAN).

This repository contains the **Android server application**, written in Kotlin.  
The phone acts as a secure server that allows the PC client to connect and interact with it.

## Features

- 📷 **Live camera streaming** using CameraX
- 💬 **Bidirectional real-time messaging** between phone and PC
- 📁 **File transfer** from phone to PC
- 📸 **Instant remote photo capture**
- 🔒 **Secure communication over HTTPS (port 8080) and Websocket secure (port 8081)**
- 🔐 **Strong encryption**
  - TLS 1.3
  - ECDHE Used for key exchange — generates the session encryption keys and Provides forward secrecy — new keys per session
  - ECDSA authentication
  - AES-256-GCM encryption
- 🤝 **Secure first-time pairing (TOFU – Trust On First Use)**

## Architecture

StreamBridge uses a **client–server architecture over LAN**.

Phone (Android app):
- Runs a local **HTTPS server using NanoHTTPD**
- Provides **WebSocket secure (WSS)** communication
- Streams camera frames
- Handles file transfer and commands

PC (Windows client):
- Desktop application built with **Kotlin + JavaFX**
- Connects to the phone over the local network
- Sends commands and receives data in real time

Phone (Server) <-- HTTPS / WSS --> Windows PC (Client)
Camera / Files / Messages Desktop UI


## Technologies Used

- **Kotlin**
- **Android SDK**
- **CameraX**
- **NanoHTTPD**
- **WebSocket Secure (WSS)**
- **TLS**
- **ECDHE**
- **ECDSA**
- **AES-256-GCM**

## Security

StreamBridge is designed with security in mind.

- All communication is encrypted using **TLS**
- Key exchange is performed using **ECDHE**
- Authentication is performed using **ECDSA**
- Messages and data are protected with **AES-256-GCM**
- First pairing uses the **TOFU (Trust On First Use)** model

## Use Cases

- Remote camera monitoring
- Secure file transfer from phone to PC
- Real-time phone–PC communication
- Remote photo capture from desktop

## Project Status

This project was developed as a personal software project demonstrating:

- Android networking
- secure communication
- real-time streaming
- cross-platform phone–desktop integration

## Related Project

The Windows desktop client is implemented separately using **Kotlin + JavaFX**.
