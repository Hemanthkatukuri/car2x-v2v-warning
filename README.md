# Smartphone-Based RSU – Car2X Demo

## Overview
This project demonstrates a **smartphone-based Car2X (Vehicle-to-Everything) communication system** developed as part of the **Automotive Software Engineering** course.  
In this system, Android smartphones act as **vehicles** and a **Roadside Unit (RSU)**, exchanging real-time data to generate safety warnings based on **vehicle-to-vehicle proximity**.

The goal is to show that basic Car2X concepts can be implemented using **standard Android devices**, **GPS**, and **UDP communication**, without specialized hardware.

---

## System Architecture
The application supports two operating modes:

### Vehicle Mode
- Periodically sends **CAM-like messages** using UDP
- Broadcasts:
  - Latitude
  - Longitude
  - Speed
  - Heading
  - Timestamp
- Each vehicle has a **unique vehicle ID**

### RSU Mode
- Receives CAM messages from **multiple vehicles**
- Maintains state for all active vehicles
- Calculates **vehicle-to-vehicle distances**
- Generates proximity-based safety warnings:
  - **DANGER**
  - **WARNING**
  - **SAFE**
- Logs received data for analysis

---

## Communication
- **Protocol:** UDP
- **Reason:** Low latency and suitability for real-time systems
- **Network:**  
  - Same Wi-Fi network  
  - Mobile hotspot  
  - Wi-Fi Direct (tested)

---

## Distance Calculation
- Uses the **Haversine formula** to calculate distances between vehicles based on GPS coordinates
- Distance thresholds determine warning levels
- GPS accuracy handling and filtering are applied to reduce instability

---

## Safety Warning Logic
Warnings are generated at the RSU based on vehicle-to-vehicle distance:

- **DANGER:** Vehicles are extremely close
- **WARNING:** Vehicles are within a moderate range
- **SAFE:** Vehicles are at a safe distance

This approach focuses on **vehicle-to-vehicle warnings**, not just vehicle-to-RSU distance.

---

## User Interface
The application UI provides:
- Mode selection (Vehicle / RSU)
- Live GPS data (latitude, longitude, speed, accuracy)
- Start and Stop controls
- Real-time safety warning visualization
- Display of multiple vehicles at the RSU

---

## Demo Video
A complete working demonstration of the system is available here:

📁 `media/DEMO_VIDEO.txt`

(The video is hosted externally for reliability and accessibility.)

---

## Project Documents
- 📄 **Project Report:** `docs/Car2X_Report.pdf`
- 📊 **Presentation Slides:** `docs/Car2X_Presentation.pdf`

---

## Individual Contributions

### Hemanth Katukuri
- Communication logic and UDP networking
- Multi-vehicle handling at the RSU
- Mobile application development
- Logging and performance evaluation

### Sai Charan Kandepi
- GPS sensing and data acquisition
- CAM message structure and protocol design
- Distance calculation and warning logic validation
- UI improvements and testing

---

## Technologies Used
- Android (Kotlin)
- UDP networking
- GPS / Location Services
- Haversine distance calculation
- Android Studio

---

## Key Learnings
- Real-time communication challenges in V2X systems
- GPS accuracy limitations and mitigation
- Multi-vehicle state management
- Practical use of UDP in mobile applications
- End-to-end Car2X system design using smartphones

---

## License
This project was developed for academic purposes as part of the **Automotive Software Engineering** course.

