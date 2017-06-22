# Audio-Voice-Recorder
Audio &amp; Voice Recorder for Android
-------------------------------------------
Audio & Voice recorder for android, which uses background / foreground service, that extends MediaBrowserService. Interaction between UI Activity and Service is performed using MediaSession, its callbacks, controllers, and other subsidiary classes. While in foreground, service shows notification with media buttons. Recording is performed using MediaRecorder, playback is performed using MediaPlayer. Recorded audio files are stored on SD card and listed in Activity to be accessible for playing and managment. App uses Visualizer class to receive WaveForm and Fft (Fast Fourier Transform) data during playback, writes it to XML and JSON files on device. From received data are decoded frequency, amplitude, magnitude and spectral density, and then displayed on the chart in one of several modes. To build real-time chart is used custom View element, which draws itself on the Canvas.

-------------------------------------------
_Main features are:_
* MVC architecture
* foreground service
* audio recording
* saving audio to SD
* audio playback
* showing notification with media controls
* writing visualization data to XML and JSON
* displaying various charts in custom View element on Canvas

-------------------------------------------
Screenshots

 ![screenshot](https://raw.githubusercontent.com/Vitaliy-B/Audio-Voice-Recorder/master/data/AVR_scrsh_01_ready.png "screenshot")
 ![screenshot](https://raw.githubusercontent.com/Vitaliy-B/Audio-Voice-Recorder/master/data/AVR_scrsh_02_rec.png "screenshot")
 ![screenshot](https://raw.githubusercontent.com/Vitaliy-B/Audio-Voice-Recorder/master/data/AVR_scrsh_03_type.png "screenshot")
 ![screenshot](https://raw.githubusercontent.com/Vitaliy-B/Audio-Voice-Recorder/master/data/AVR_scrsh_04_play_AF.png "screenshot")
 ![screenshot](https://raw.githubusercontent.com/Vitaliy-B/Audio-Voice-Recorder/master/data/AVR_scrsh_04_play_MF.png "screenshot")
 ![screenshot](https://raw.githubusercontent.com/Vitaliy-B/Audio-Voice-Recorder/master/data/AVR_scrsh_04_play_SDF.png "screenshot")
 ![screenshot](https://raw.githubusercontent.com/Vitaliy-B/Audio-Voice-Recorder/master/data/AVR_scrsh_05_pause.png "screenshot")
 ![screenshot](https://raw.githubusercontent.com/Vitaliy-B/Audio-Voice-Recorder/master/data/AVR_scrsh_06_saved.png "screenshot")
