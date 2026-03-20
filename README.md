# 🚂 Railway Evolution (Create Addon)

**Railway Evolution** is a technical addon for the [Create Mod](https://modrinth.com/mod/create) that gives trains a "brain". It implements a custom pathfinding and collision avoidance system to make rail networks smarter and more autonomous.

## 🧠 The Logic
The core of this mod is a **BFS (Breadth-First Search) rail scanner**. Instead of relying solely on vanilla signals, trains actively "scan" the track ahead to:
* **Calculate braking distances** based on current speed.
* **Detect obstacles** and other trains in real-time.
* **Negotiate junctions** by checking which branch is clear.
* **Prevent deadlocks** in complex station throat layouts.

## ✨ Key Features
* **Smart Braking:** Smooth deceleration when approaching other trains or occupied stations.
* **Dynamic Rerouting:** If a junction is blocked, the train attempts to find an alternative path.
* **Parallel Track Awareness:** Intelligent scanning that ignores trains on adjacent tracks.

## 🛠 Technical Details
* **Platform:** Minecraft Forge / NeoForge
* **Version:** 1.20.1
* **Core Dependency:** Create 0.5.1+

## ⚠ Disclaimer
This project is currently in **BETA**. 
Developed by **Fizzy_lovely** (1xCodeTeam). 

> *Note: This mod was built with a lot of passion and one working hand (thanks to an Ilizarov apparatus). Please be patient with bug fixes!*

## 📜 License
This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.
