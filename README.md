# P2PMonitoring
This project is an attempt to create a peer-to-peer video monitoring system for Android devices primarily and any other clients implementing the protocol in the future. Any camera enabled Android device may run as a server (p2pcamera app) to accept connections from clients (p2pmonitor app for Android) and stream media data to them. Protocol implies a possibility for clients to control server part requesting available features and managing the camera parameters as if it was local.

# Project plan and progress
## Phase #0 - «POC»
- [x] Android server app
- [x] Server: open camera and display preview
- [x] Server: media encoder
- [x] Server: encoded stream transmission
- [x] Android client app
- [x] Client: media stream reception
- [x] Client: media decoder
- [x] Client: show decoded stream
- [x] Protocol: flash management
- [x] Server: SSDP based port mapping over UPnP
## Phase #1 - «Stabilization»
- [x] Create app icons
- [x] Add string localizations
- [ ] Remaster enums via `@IntDef` or static `byte`->value maps
- [x] Refactor `Message` acquisition via `obtain()`
- [ ] Secure transport layer
- [ ] Create unit tests
- [x] Client: control frame rotation
- [ ] Protocol: capture (high-res) photo
- [x] Server: stop encoder when last client disconnects
- [ ] Server: detect connection drops/switchovers/IP renewals etc.
- [ ] Server: periodic port mapping check and refresh
- [x] Server: fix image freezes
## Phase #2 - «Camera controls»
- [ ] Server: collect map of cameras to their characteristics
- [ ] Server: screen dimming with configurable timeout
- [ ] Protocol: choose desired camera
- [ ] Protocol: choose camera resolution
- [ ] Protocol: choose AF/MF
## Phase #3 - «UX»
- [ ] Client & Server: color themes
- [ ] Client: action icons
- [ ] Client: rotate icons instead of screen orientation
- [ ] Client: server address history
- [ ] Server: client statistics (address, connection time, etc.)
## Phase #4 - «Media store»
- [ ] PC client app
- [ ] monitoring data saving 
- [ ] configurable cleanup period
- [ ] automatic connection restoring
- [ ] reg as a web camera
- [ ] OBS client
## Phase #5 - «Scaling»
- [ ] Server: work with several cameras to serve different sources to different clients independently
- [ ] Server: per-camera client statistics
---
## Nice to have features
- [ ] Wrap camera server into foreground service
- [ ] Protocol: audio streaming
- [ ] Server: choose interface (IP address) to bind to
- [ ] Server: choose gateway (external IP address) to forward from 
