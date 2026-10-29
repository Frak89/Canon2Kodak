# AmpyCanon Print — rev2 (Android)

Applicazione Android **sperimentale** per Canon EOS R6 Mark II con CCAPI, testabile su Pixel 7 Pro.

**Non è un'app avviabile dalla SD della Canon e non modifica il firmware.**

## Funzioni

- Connessione Canon tramite CCAPI HTTP su rete Wi-Fi locale, con utente/password opzionali (Basic/Digest MD5).
- Esplorazione file dalle SD della fotocamera (JPEG `.JPG`, RAW/C-RAW `.CR3` e HEIF `.HIF`).
- Anteprima remota tramite `kind=display` / `kind=thumbnail` (dipende dal supporto della Canon).
- Download **originale bit-per-bit** di JPEG e RAW, con streaming per RAW grandi (senza decodifica o ricompressione).
- Importazione locale di `.CR3`, `.CR2`, `.DNG`, JPEG, HEIF tramite selettore Android.
- Salvataggio JPEG in `Immagini/AmpyCanonPrint` e RAW in `Download/AmpyCanonPrint` tramite MediaStore.
- Esportazione **JPEG di anteprima** per CR3 dalla Canon (`kind=display` / `kind=thumbnail`) e condivisione con Kodak. **Non è sviluppo RAW** e la risoluzione dipende dalla fotocamera.
- **APRI EDITOR RAW**: apre il file `.CR3`, `.CR2` o `.DNG` originale nell'app scelta fra quelle installate. Per un RAW ancora sulla Canon lo scarica prima, senza conversione.
- **SCEGLI APP EDITOR RAW**: cambia l'editor preferito; scelta salvata sul telefono. L'editor deve dichiarare di poter ricevere il formato; l'app non garantisce che sappia decodificarlo.
- Condivisione a un'app compatibile e collegamento all'app Kodak (senza protocollo Bluetooth proprietario).

## Preparazione Canon

1. Abilitare **CCAPI** sulla R6 Mark II (se manca la voce, utilizzare il tool di attivazione ufficiale Canon).
2. Configurare la connessione Wi-Fi CCAPI. Nella schermata Canon controllare URL, IP e porta (es. `http://192.168.1.2:8080`).
3. Connettere Android alla rete Wi-Fi della fotocamera o alla stessa rete del router. Se appare **rete senza Internet**, scegliere di restare collegati.
4. Inserire URL, eventuale username/password e premere **CONNETTI**.
5. Premere **CERCA FOTO SU ENTRAMBE LE SD**, selezionare JPEG o CR3 e **SALVA ORIGINALE**.
6. Per importare un RAW già presente sul telefono, usare **IMPORTA RAW / JPEG DAL TELEFONO**.
7. Selezionare un `.CR3` e premere **APRI EDITOR RAW**. La prima volta scegliere l'app preferita: la scelta sarà riutilizzata. Premere **SCEGLI APP EDITOR RAW** per modificarla. Le app non compatibili con il MIME RAW potrebbero non essere elencate.

## Kodak Mini 2 Retro

La Kodak stampa fotografie tramite la propria app. Questa rev2 può salvare JPEG nella Galleria o inoltrarli alla condivisione Android. **Non si garantisce che l'app Kodak accetti direttamente ACTION_SEND**: in tal caso aprire Kodak Photo Printer e scegliere la foto salvata. I RAW `.CR3` non sono stampabili direttamente: l'app può **estrarre un JPEG di anteprima dalla Canon** per la Kodak, se disponibile, oppure puoi sviluppare il RAW separatamente con un programma dedicato. L'estrazione dell'anteprima non equivale a una conversione RAW completa.

## Sicurezza e limiti

- App in sola lettura verso la Canon: non scatta, non cancella, non formatta, non scrive sulla SD.
- Accetta soltanto indirizzi HTTP IPv4 privati; non segue redirect e non invia credenziali a host differenti.
- La versione di Canon CCAPI e i formati di risposta possono variare: la scansione ha un limite di 160 richieste e 2000 elementi, quindi **non è garantito l'elenco completo delle schede**.
- Anteprime RAW dipendono dal supporto `kind=display` della Canon. RAW locali non hanno un decoder incorporato.
- Autenticazione Digest supportata solo con algoritmo MD5 e `qop=auth`; configurazioni diverse potrebbero richiedere adattamenti.
- Copie RAW senza perdita: nessuna demosaicizzazione, ritaglio o conversione.
- È un prototipo **non testato su una fotocamera fisica** in questa sessione.

## Build Android Studio

- JDK 17
- Android SDK API 36
- Android Studio recente (AGP 8.13.2, Gradle 8.13)

Apri la cartella `AmpyCanonPrint_rev1` in Android Studio, lascia completare la sincronizzazione Gradle, poi **Build → Build APK(s)**.

Per compilare direttamente con GitHub Actions: carica la cartella nel tuo repo, vai in **Actions → Android APK Debug → Run workflow** e scarica l'artifact `Canon2Kodak-rev2-debug-apk`.

Attenzione: questa distribuzione contiene i **sorgenti**. Non contiene un APK già compilato.

## Fonti tecniche
- Canon CCAPI: https://www.usa.canon.com/support/sdk
- Guida attivazione: https://downloads.canon.com/sdk/CameraControlAPI_OperationGuide_EN.pdf
- Canon SDK compatibilità: https://asia.canon/en/campaign/developerresources/sdk
