# StreamBridge – Android Server

StreamBridge is a secure real-time communication system that connects an Android phone with a Windows PC over a local network (LAN).

This repository contains the **Android server application**, written in Kotlin.  
The phone acts as a secure server that allows the PC client to connect and interact with it.

---

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

---

## Architecture

StreamBridge uses a **client–server architecture over LAN** — no internet connection or cloud service required.

Android Phone (Server) <-- HTTPS / WSS --> Windows PC (Client)
Camera / Files / Messages Desktop UI

Phone (Android app):
- Runs a local **HTTPS server using NanoHTTPD**
- Provides **WebSocket secure (WSS)** communication
- Streams camera frames
- Handles file transfer and commands

PC (Windows client):
- Desktop application built with **Kotlin + JavaFX**
- Connects to the phone over the local network
- Sends commands and receives data in real time

---

## Security

StreamBridge is designed with security in mind.

## Cryptography

- All communication is encrypted using **TLS**
- Key exchange is performed using **ECDHE**
- Authentication is performed using **ECDSA**
- Messages and data are protected with **AES-256-GCM**
- Pairing model: **TOFU (Trust On First Use)**
On first connection the PC receives the phone's self-signed certificate and pins it locally
Every subsequent connection verifies against the pinned certificate — a rogue device on the network cannot impersonate the phone
Pairing is initiated either by scanning a QR code or via an Auto-Discover prompt that requires explicit acceptance on the phone

---

## Technologies Used

- **Kotlin**- primary language
- **HTML**
- **Android SDK**
- **CameraX** - camera streaming pipeline
- **NanoHTTPD** - embedded HTTPS server
- **java-WebSocket Secure (WSS)**
- **Android Keystore** - private key storage (key never leaves the secure enclave)
- **JmDNS / mDNS** - local network discovery
- **TLS**
- **ECDHE**
- **ECDSA**
- **AES-256-GCM**

---

## First-time pairing

1. Start the StreamBridge app on your phone — the server starts automatically

2. On the Windows client, click **Show QR Code** and scan it with the phone, or click **Auto-Discover Devices**

3. Accept the connection prompt on the phone — the certificate is pinned and all future connections are automatic

---

## Use Cases

- Remote camera monitoring
- Secure file transfer from phone to PC
- Real-time phone–PC communication
- Remote photo capture from desktop

---

## Project Status

This project was developed as a personal software project demonstrating:

- Android networking
- secure communication
- real-time streaming
- cross-platform phone–desktop integration

---

## Related Project

The Windows desktop client is implemented separately using **Kotlin + JavaFX**.
