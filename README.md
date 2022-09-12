# P2PMonitoring
This project is an attempt to create a peer-to-peer video monitoring system
for Android devices primarily and any other clients implementing the protocol in the future.
Any camera enabled Android device may run as a server (p2pcamera app)
to accept connections from clients (p2pmonitor app for Android) and stream media data to them.
Protocol implies a possibility for clients to control server part requesting available features
and managing the camera parameters as if it was local.

##Source code and building
The project contains compile-time annotations.
While source code gets built without errors, you may find unresolved references during source code overview.
In order to eliminate such faulty error highlights consider installing
[bali-intellij-plugin](https://github.com/coolsoftrf/bali-intellij-plugin)
(an evolution of [lombok-intellij-plugin](https://github.com/mplushnikov/lombok-intellij-plugin) 
which supports Enum annotations used in this project)

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
- [ ] Create unit tests
- [x] Client: control frame rotation
- [ ] Protocol: capture (high-res) photo on the fly
- [x] Server: stop encoder when last client disconnects
- [ ] Server: check ownership of current port mapping before erasing 
- [ ] Server: detect connection drops/switchovers/IP renewals etc.
- [ ] Server: periodic port mapping check and refresh
- [x] Server: fix image freezes on 1st connection / last disconnection on API 25 devices
- [x] Server: fix image turns 90 deg CCW on 1st connection / last disconnection on API 25 devices
- [ ] Client: fix image out of sync on slow devices
- [ ] Client: add media stream watchdog and connection revival
## Phase #2 - «Security»
- [x] Authentication and Authorization
- [x] Payload ciphering
- [x] Secure transport layer
- [x] Server: user management preferences
## Phase #3 - «Camera controls»
- [ ] Server: collect map of cameras to their characteristics
- [ ] Server: screen dimming with configurable timeout
- [ ] Protocol: choose desired camera
- [ ] Protocol: choose camera resolution
- [ ] Protocol: choose AF/MF
## Phase #4 - «UX»
- [ ] Client & Server: color themes
- [ ] Client: action icons
- [ ] Client & Server: rotate UI controls instead of screen orientation
- [ ] Client: server address & login history
- [ ] Server: client statistics (address, connection duration, etc.)
## Phase #5 - «Media store»
- [ ] PC client app
- [ ] monitoring data saving 
- [ ] configurable cleanup period
- [ ] automatic connection restoring
- [ ] reg as a web camera
- [ ] OBS client
## Phase #6 - «Scaling»
- [ ] Server: work with several cameras to serve different sources to different clients independently
- [ ] Server: per-camera client statistics
---
## Nice to have features
- [ ] Wrap camera server into foreground service
- [ ] Protocol: audio streaming
- [ ] Server: choose interface (IP address) to bind to
- [ ] Server: choose gateway (external IP address) to forward from 
