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
- [ ] Server: UPnP over NAT-PMP
- [ ] Protocol: audio streaming
## Phase #1 - «Stabilization»
- [ ] Client: control frame rotation
- [ ] Protocol: capture (high-res) photo
- [ ] Server: stop encoder when no clients are connected
## Phase #2 - «Camera controls»
- [ ] Server: collect map of cameras to their characteristics
- [ ] Protocol: choose desired camera
- [ ] Protocol: choose camera resolution
- [ ] Protocol: choose AF/MF
## Phase #3 - «UX»
- [ ] Client: action icons
- [ ] Client: rotate icons instead of screen orientation
- [ ] Server: client statistics (address, connection time, etc.)
## Phase #4 - «Media store»
- [ ] PC client app to save monitoring data for a configured period of time
## Phase #5 - «Scaling»
- [ ] Server: work with several cameras to serve different sources to different clients independently
- [ ] Server: per-camera client statistics
