# 💤 NEXA

## 🚗 Descripción general
**NEXA** es una aplicación Android inteligente diseñada para **detectar signos de somnolencia o fatiga en tiempo real** mediante el uso de la cámara frontal y un modelo de **inteligencia artificial (IA)**.  

Su objetivo es **alertar automáticamente** cuando el sistema detecta que el usuario presenta señales de cansancio, como:
- Ojos cerrados prolongadamente  
- Bostezos repetidos  
- Pérdida temporal del rostro frente a la cámara  

El sistema puede ser aplicado en **vehículos**, **entornos laborales** o **proyectos de investigación** sobre seguridad y atención humana.

---

## 🧠 Características principales
- 📷 **Análisis en tiempo real** mediante la cámara del dispositivo  
- 🤖 **Modelo de IA integrado** (`DrowsinessClassifier`) entrenado para detectar somnolencia visual  
- ⚠️ **Alertas automáticas** ante señales de riesgo  
- 🔔 **Sistema de alarmas** con sonido, diálogo y notificación visual  
- 🧩 **Interfaz moderna** desarrollada con **Jetpack Compose (Material 3)**  
- 🧠 **Visualización de depuración (debug)** que muestra las zonas detectadas (rostro, ojos, boca) sobre la cámara  

---

## 🧩 Arquitectura del proyecto
El proyecto combina visión por computadora, análisis de flujo y una arquitectura reactiva moderna.

| Capa | Descripción |
|------|--------------|
| **UI (Compose)** | Implementa la interfaz principal (`MainScreen`) y el panel de información en tiempo real |
| **Analyzer (CameraX + ML)** | Captura frames desde la cámara y los analiza con `DrowsinessAnalyzer` |
| **Classifier (TFLite)** | Evalúa los frames y devuelve probabilidades de ojos cerrados, bostezos y pérdida de rostro |
| **Alert System** | Monitorea el estado de somnolencia y activa alarmas automáticas según el tipo de evento |
| **Overlay Canvas** | Dibuja los rectángulos de detección y texto de depuración sobre la vista de cámara |

---

## 🚀 Cómo usar

1. Abre el proyecto en **Android Studio (versión Giraffe o superior)**.  
2. Concede los **permisos de cámara** al iniciar la app.  
3. Apunta la cámara hacia tu rostro.  
4. El sistema analizará automáticamente tu **nivel de cansancio**.  

Si se detecta un **estado de riesgo**:
- 🔊 Sonará una **alarma**
- ⚠️ Se mostrará una **notificación visual**
- ✅ Podrás confirmar si todo está bien o necesitas un descanso

---

## 📊 Interfaz de usuario

### Panel superior con métricas:
- 👁️ **Cansancio de ojos**  
- 😮 **Bostezos**  
- ❌ **Rostro perdido**

### Overlay gráfico:
- 🟩 Rostro — Verde  
- 🟦 Ojos — Celeste  
- 🟨 Boca — Amarillo  

Además, cuenta con un **diálogo emergente** en caso de alerta, con botones de confirmación.

---

## 🔔 Tipos de alerta

| Tipo | Descripción | Acción |
|------|--------------|--------|
| `eyes` | Ojos cerrados durante varios segundos | 🔊 Activa alarma sonora |
| `yawn` | Múltiples bostezos consecutivos | 🔔 Notificación + sonido |
| `lost` | El rostro deja de detectarse | ⚠️ Alerta visual |

---

## 📱 Tecnologías utilizadas

- 🧠 **TensorFlow Lite** – Modelo de IA para detección facial  
- 📸 **CameraX** – Captura de video en tiempo real  
- 🎨 **Jetpack Compose** – Interfaz moderna y declarativa  
- ⚙️ **Coroutines / Flow** – Procesamiento asíncrono  
- 🧩 **Android ViewModel** – Manejo de estado y ciclo de vida  
- 💎 **Material 3** – Diseño limpio y adaptable

---

## 🧑‍💻 Autor

**Gian Franco Andree Yupanqui Zapana**  
Estudiante de la carrera profesional Ing. Sistemas e Informatica, apacionado de las nuevas tecnologias.


---

## ⚙️ Dependencias principales
Asegúrate de incluir las siguientes dependencias en tu `build.gradle`:

```gradle
implementation "androidx.compose.ui:ui:<latest_version>"
implementation "androidx.compose.material3:material3:<latest_version>"
implementation "androidx.camera:camera-core:<latest_version>"
implementation "androidx.camera:camera-camera2:<latest_version>"
implementation "androidx.camera:camera-lifecycle:<latest_version>"
implementation "androidx.camera:camera-view:<latest_version>"
implementation "org.tensorflow:tensorflow-lite-task-vision:<latest_version>"

⚠️ Reemplaza <latest_version> por las versiones actuales compatibles con tu proyecto.
---
---
---

