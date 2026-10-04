# OpenJump Privacy Policy / Política de privacidad / Politique de confidentialité / Datenschutzerklärung / Informativa sulla privacy / Política de Privacidade / Gizlilik Politikası

Available in: English, Español, Français, Deutsch, Italiano, Português (Brasil), Português (Portugal), Türkçe.

**Public page:** the public Notion policy is updated manually; it must be synchronized with this document before any new release. Parity is not claimed here.

**Last updated / Última actualización / Dernière mise à jour / Zuletzt aktualisiert / Ultimo aggiornamento / Última atualização / Son güncelleme: October 3, 2026 / 3 de octubre de 2026 / 3 octobre 2026 / 3. Oktober 2026 / 3 ottobre 2026 / 3 de outubro de 2026 / 3 Ekim 2026**

## English

OpenJump processes measurements on-device, has no account or OpenJump backend, and does not request Internet permission. It does not use automatic analytics or telemetry. This does not mean copies never leave the device: user-initiated sharing, export, shared video storage and support email are described below.

### Data stored on the device

OpenJump stores user-entered or generated saved information in its local Android Room database, in the app's private storage. This may include athlete names and, when provided, date of birth, sex, notes, weight, height, group membership, testing sessions, jump measurements, Encoder sessions, marked events, metrics, calibration data and trajectory-analysis samples. A saved result may retain only the selected video's URI reference; video bytes are not stored in Room.

This information is used only to provide the app's measurement, history, comparison and export features.

### Camera and videos

Camera access is used only when the user chooses to record a video. A video recorded by OpenJump is published to the device's shared `Movies/OpenJump` collection so it remains available to the user. Other applications on the device, including gallery or cloud-sync applications, may be able to view or synchronize files in that shared collection under their own privacy policies.

Videos selected for import are read from the location chosen by the user and are not copied into the OpenJump database. Deleting an OpenJump result does not delete its associated imported or recorded video. Shared videos can be deleted separately with the device's gallery or file manager.

On Android 8 and 9, OpenJump requests legacy storage write access only to publish a recording to `Movies/OpenJump`. On newer Android versions it uses Android's scoped media storage. OpenJump does not request microphone, location or Internet permission.

### Sharing and export

OpenJump has no automatic analytics/telemetry or backend. The user may explicitly save a recording to shared `Movies/OpenJump`, export JSON/CSV or a trajectory video, or create manual backup v1. The backup file is not encrypted or authenticated; the selected document provider may synchronize it. Destination apps/services handle their copies under their own policies.

The About screen can open an editable support email draft to **openjump.app@gmail.com**. It pre-fills app version name/code, Android release/API level, manufacturer and model, plus blank prompts for the user's description and reproduction steps. It does not automatically attach unique device/user IDs, account data, video, measurement/result, file path or video URI. OpenJump does not send the draft: the user reviews/edits it and chooses whether to send it in an email app. The user may add personal or sensitive information. The email app/provider then handles the sent message; its processing, retention and deletion practices are not controlled or guaranteed by OpenJump. About also offers user-initiated actions to copy the same basic installation/device diagnostics described above to the clipboard, or share them with an app you choose through Android's chooser.

OpenJump does not sell personal data or use it for advertising, profiling or automatic analytics.

### Retention, deletion and backup

Local data remains on the device until the user deletes an individual saved result, clears OpenJump's app data, or uninstalls the app. Archiving an athlete or group preserves its historical measurements. Deleting a saved result removes its Room record and associated analysis children, but not a separately stored video or export.

Android cloud backup and device-transfer backup are disabled for OpenJump. Uninstalling the app or clearing its data can therefore permanently remove the local database. User-created exports and videos in shared storage must be deleted separately.

### Manual backup

The Settings screen also offers a separate, user-initiated manual backup. Its
versioned JSON v1 is emitted from Room 10 and contains the complete Room 10 data graph; it remains compatible with v1 backups from Room 9 and Room 10. It includes names, dates,
sex, notes, anthropometrics, groups, testing, results, marked events, calibration
and trajectory samples. It excludes videos, video links/URIs, cache, preferences
and temporary sessions. Restoring is an explicit total replacement with an
atomic rollback-safe transaction; it does not merge data. The file is not
encrypted or authenticated, so the selected document provider may synchronize
it. Android cloud backup and device transfer remain disabled.

### Children and health information

OpenJump does not automatically transmit child or health information. If you choose to include such information in a support email, that message is sent through your email app/provider to the support address. Do not send information about another person unless you have an appropriate lawful basis and consent. OpenJump is a sports-performance aid, not a medical, clinical or diagnostic device.

### Security and changes

Data is protected by Android's application sandbox while it remains in OpenJump's private storage. No local-only application can guarantee absolute security, particularly on a compromised device. Material changes to this policy will be reflected in this document and in the policy shown inside the app.

### Open source

OpenJump source code is published at https://github.com/cortig00/OpenJump under the **GPL-3.0-or-later** license. Anyone may inspect, modify and redistribute it under the terms of that license. Official releases are distributed through Google Play and the channels identified by the repository maintainers.

Publishing source code does not disclose user information: OpenJump's database, videos, exports, signing keys and local configuration are not part of the source repository.

### Contact

Questions, or want to request deletion of a support message you chose to send? Contact **openjump.app@gmail.com**. No specific retention period or automatic deletion of messages is promised.

## Español

OpenJump procesa las mediciones en el dispositivo, no tiene cuentas ni backend propio y no solicita permiso de Internet. No usa analítica ni telemetría automática. Esto no significa que las copias nunca salgan del dispositivo: a continuación se describen los vídeos compartidos, las exportaciones y los correos de soporte que inicia el usuario.

### Datos guardados en el dispositivo

OpenJump guarda la información guardada que el usuario introduce o crea en su base de datos Android Room local, dentro del almacenamiento privado de la app. Puede incluir nombres de atletas y, si se facilitan, fecha de nacimiento, sexo, notas, peso, estatura, pertenencia a grupos, sesiones de testing, mediciones de salto, sesiones Encoder, eventos marcados, métricas, calibraciones y muestras del análisis de trayectoria. Un resultado guardado puede conservar solo la URI de referencia del vídeo elegido; Room no guarda los bytes del vídeo.

La información solo se utiliza para proporcionar las funciones de medición, histórico, comparación y exportación de la aplicación.

### Cámara y vídeos

La cámara se utiliza únicamente cuando el usuario decide grabar. Los vídeos grabados por OpenJump se publican en la colección compartida `Movies/OpenJump` para que sigan estando disponibles. Otras aplicaciones del dispositivo, incluidas galerías o servicios de sincronización, pueden ver o sincronizar esos archivos conforme a sus propias políticas.

Los vídeos seleccionados para importar se leen desde la ubicación elegida y no se copian a la base de datos. Eliminar un resultado de OpenJump no elimina el vídeo importado o grabado asociado. Los vídeos compartidos deben eliminarse por separado desde la galería o gestor de archivos.

En Android 8 y 9 se solicita acceso de escritura legacy únicamente para publicar una grabación en `Movies/OpenJump`. En versiones posteriores se usa el almacenamiento multimedia delimitado de Android. OpenJump no solicita permisos de micrófono, ubicación o Internet.

### Compartición y exportación

OpenJump no usa analítica/telemetría automática ni backend. El usuario puede guardar explícitamente una grabación en `Movies/OpenJump`, exportar JSON/CSV o un vídeo de trayectoria, o crear la copia manual v1. Esta copia no está cifrada ni autenticada; el proveedor de documentos elegido podría sincronizarla. Las aplicaciones/servicios de destino tratan sus copias según sus propias políticas.

La pantalla About puede abrir un borrador editable de correo para **openjump.app@gmail.com**. Incluye versión/código de la app, versión/API de Android, fabricante y modelo, y campos vacíos para describir el problema y pasos para reproducirlo. No adjunta automáticamente IDs únicos del dispositivo/usuario, datos de cuenta, vídeo, medición/resultado, ruta ni URI del vídeo. OpenJump no envía el borrador: el usuario lo revisa/edita y decide si lo envía desde una aplicación de correo. Puede añadir datos personales o sensibles. La aplicación/proveedor de correo trata el mensaje enviado; OpenJump no controla ni garantiza sus prácticas de tratamiento, conservación o borrado. About también permite, si lo eliges, copiar al portapapeles los mismos diagnósticos básicos de instalación/dispositivo descritos arriba o compartirlos con una app que elijas mediante el selector de Android.

OpenJump no vende datos personales ni los usa para publicidad, perfiles o analítica automática.

### Conservación, borrado y backup

Los datos permanecen en el dispositivo hasta que el usuario elimina un resultado, borra los datos de OpenJump o desinstala la aplicación. Archivar un atleta o grupo conserva sus mediciones históricas. Eliminar un resultado borra su registro Room y los análisis asociados, pero no vídeos o exportaciones almacenados por separado.

El backup en la nube y la transferencia entre dispositivos de Android están desactivados. Desinstalar o borrar los datos puede eliminar permanentemente la base local. Los vídeos y exportaciones creados por el usuario deben eliminarse por separado.

### Copia manual

Ajustes ofrece además una copia manual independiente, iniciada por el usuario.
Su JSON v1 se emite desde Room 10 y contiene el grafo completo Room 10; también acepta copias v1 de Room 9 y Room 10. Incluye nombres, fechas,
sexo, notas, antropometría, grupos, testing, resultados, eventos, calibraciones y
muestras de trayectoria. Excluye vídeos, enlaces/URI de vídeo, caché,
preferencias y sesiones temporales. Restaurar sustituye todos los datos tras una
confirmación explícita, con una transacción atómica y rollback; no combina datos.
El archivo no está cifrado ni autenticado y el proveedor elegido podría
sincronizarlo. El backup cloud y la transferencia entre dispositivos de Android
siguen desactivados.

### Menores e información de salud

OpenJump no transmite automáticamente información de menores ni de salud. Si decides incluirla en un correo de soporte, el mensaje se envía mediante tu app/proveedor de correo a la dirección de soporte. No envíes datos de otra persona si no tienes la base legal y el consentimiento apropiados. OpenJump es una ayuda para el rendimiento deportivo, no un dispositivo médico, clínico ni diagnóstico.

### Seguridad y cambios

Mientras los datos están en el almacenamiento privado de OpenJump, quedan protegidos por el sandbox de Android. Ninguna aplicación exclusivamente local puede garantizar seguridad absoluta, especialmente en dispositivos comprometidos. Los cambios materiales se reflejarán en este documento y en la política mostrada dentro de la app.

### Código abierto

El código fuente de OpenJump se publica en https://github.com/cortig00/OpenJump bajo la licencia **GPL-3.0-or-later**. Cualquier persona puede inspeccionarlo, modificarlo y redistribuirlo conforme a los términos de la licencia. Las versiones oficiales se distribuyen mediante Google Play y los canales identificados por los responsables del repositorio.

Publicar el código no expone información de usuarios: la base de datos, vídeos, exportaciones, claves de firma y configuración local de OpenJump no forman parte del repositorio de código.

### Contacto

¿Tienes preguntas o quieres solicitar la eliminación de un mensaje de soporte que enviaste voluntariamente? Escribe a **openjump.app@gmail.com**. No se promete un plazo concreto de conservación ni el borrado automático de mensajes.

## Français

OpenJump traite les mesures sur l’appareil, n’a ni compte ni backend OpenJump et ne demande pas l’autorisation Internet. L’application n’utilise pas d’analyse ni de télémétrie automatique. Cela ne signifie pas que les copies ne quittent jamais l’appareil : les vidéos partagées, exportations et e-mails de support initiés par l’utilisateur sont décrits ci-dessous.

### Données stockées sur l’appareil

OpenJump stocke les informations enregistrées que vous saisissez ou créez dans sa base Android Room locale, dans le stockage privé de l’application. Cela peut inclure les noms des athlètes et, lorsqu’ils sont fournis, la date de naissance, le sexe, les notes, le poids, la taille, l’appartenance à un groupe, les séances de test, les mesures de saut, les sessions Encoder, les événements marqués, les métriques, les données de calibration et les échantillons d’analyse de trajectoire. Un résultat enregistré peut conserver uniquement l’URI de référence de la vidéo sélectionnée ; les octets vidéo ne sont pas stockés dans Room. Ces informations servent aux fonctions de mesure, d’historique, de comparaison et d’exportation.

### Caméra et vidéos

L’accès à la caméra est utilisé uniquement lorsque vous choisissez d’enregistrer une vidéo. Une vidéo enregistrée par OpenJump est publiée dans la collection partagée `Movies/OpenJump`. D’autres applications de l’appareil, notamment les galeries ou services de synchronisation cloud, peuvent consulter ou synchroniser ces fichiers selon leurs propres politiques de confidentialité.

Les vidéos importées sont lues depuis l'emplacement choisi par vous et ne sont pas copiées dans la base de données OpenJump. La suppression d'un résultat ne supprime pas la vidéo associée ; les vidéos partagées peuvent être supprimées séparément avec la galerie ou le gestionnaire de fichiers. Sous Android 8 et 9, OpenJump demande l'autorisation d'écriture legacy uniquement pour publier un enregistrement. Sur les versions plus récentes, l'application utilise le stockage multimédia limité d'Android. OpenJump ne demande pas les autorisations de microphone, de localisation ou Internet.

### Partage et exportation

OpenJump n’utilise pas d’analyse/télémétrie automatique ni de backend. Vous pouvez explicitement enregistrer une vidéo dans `Movies/OpenJump`, exporter JSON/CSV ou une vidéo de trajectoire, ou créer la sauvegarde manuelle v1. Celle-ci n’est ni chiffrée ni authentifiée ; le fournisseur de documents choisi peut la synchroniser. Les applications/services de destination traitent leurs copies selon leurs propres politiques.

À propos peut ouvrir un brouillon modifiable destiné à **openjump.app@gmail.com**. Il préremplit la version/code de l’application, la version/API Android, le fabricant et le modèle, avec des champs vides pour décrire le problème et les étapes de reproduction. Aucun identifiant unique d’appareil/utilisateur, compte, vidéo, mesure/résultat, chemin ou URI vidéo n’est joint automatiquement. OpenJump n’envoie pas le brouillon : vous le relisez/modifiez et choisissez de l’envoyer dans une application de messagerie. Vous pouvez ajouter des données personnelles ou sensibles. L’application/fournisseur de messagerie traite le message envoyé ; OpenJump ne contrôle ni ne garantit ses pratiques de traitement, conservation ou suppression. À propos propose aussi, si vous le choisissez, de copier les mêmes diagnostics de base d’installation/de l’appareil décrits ci-dessus dans le presse-papiers ou de les partager avec l’application de votre choix via le sélecteur Android.

OpenJump ne vend pas de données personnelles et ne les utilise pas à des fins publicitaires, de profilage ou d’analyse automatique.

### Conservation, suppression et sauvegarde

Les données locales restent sur l’appareil jusqu’à la suppression d’un résultat, l’effacement des données OpenJump ou la désinstallation de l’application. Archiver un athlète ou un groupe conserve son historique de mesures. La suppression d’un résultat supprime son enregistrement Room et les éléments d’analyse associés, mais pas une vidéo ou une exportation stockée séparément.

La sauvegarde cloud Android et le transfert entre appareils sont désactivés pour OpenJump. La désinstallation ou l’effacement des données peut donc supprimer définitivement la base locale. Les exportations et vidéos créées dans le stockage partagé doivent être supprimées séparément.

### Sauvegarde manuelle

Les réglages proposent aussi une sauvegarde manuelle distincte, déclenchée par
l'utilisateur. Son JSON v1 est émis depuis Room 10 et contient le graphe complet Room 10 ; il accepte aussi les sauvegardes v1 de Room 9 et Room 10. Il contient les noms,
dates, sexe, notes, données anthropométriques, groupes, tests, résultats,
événements, calibrations et échantillons de trajectoire. Il exclut les vidéos, URI/liens vidéo,
cache, préférences et sessions temporaires. La restauration remplace toutes les
données après confirmation, dans une transaction atomique avec rollback; elle ne
fusionne pas les données. Le fichier n’est ni chiffré ni authentifié et le
fournisseur choisi peut le synchroniser. Les sauvegardes cloud Android et le
transfert entre appareils restent désactivés.

### Enfants et informations de santé

OpenJump ne transmet pas automatiquement d’informations concernant des enfants ou la santé. Si vous choisissez d’en inclure dans un e-mail au support, le message est envoyé via votre application/fournisseur de messagerie à l’adresse de support. N’envoyez pas les informations d’une autre personne sans base légale ni consentement appropriés. OpenJump est un outil d’aide à la performance sportive, pas un dispositif médical, clinique ou diagnostique.

### Sécurité, code source et contact

Les données sont protégées par le sandbox Android tant qu’elles restent dans le stockage privé d’OpenJump. Aucune application exclusivement locale ne peut garantir une sécurité absolue, notamment sur un appareil compromis. Toute modification importante sera reflétée dans cette politique et dans celle affichée dans l’application.

Le code source d’OpenJump est publié sur https://github.com/cortig00/OpenJump sous licence **GPL-3.0-or-later**. Les bases de données, vidéos, exportations, clés de signature et configurations locales ne font pas partie du dépôt source.

Une question ou une demande de suppression d’un message de support que vous avez choisi d’envoyer ? Contactez **openjump.app@gmail.com**. Aucune durée de conservation précise ni suppression automatique n’est promise.

## Deutsch

OpenJump verarbeitet Messungen auf dem Gerät, hat weder Konto noch OpenJump-Backend und fordert keine Internetberechtigung an. Es verwendet keine automatische Analyse oder Telemetrie. Das bedeutet nicht, dass Kopien nie das Gerät verlassen: Vom Nutzer veranlasste Freigaben, Exporte, gemeinsam gespeicherte Videos und Support-E-Mails werden unten beschrieben.

### Auf dem Gerät gespeicherte Daten

OpenJump speichert gespeicherte, von Ihnen eingegebene oder erstellte Informationen lokal in der Android-Room-Datenbank im privaten App-Speicher. Dazu können Athletennamen und, sofern angegeben, Geburtsdatum, Geschlecht, Notizen, Gewicht, Größe, Gruppenzugehörigkeit, Testsitzungen, Sprungmessungen, Encoder-Sitzungen, markierte Ereignisse, Messwerte, Kalibrierungsdaten und Trajektorienanalyseproben gehören. Ein gespeichertes Ergebnis kann nur die Referenz-URI des ausgewählten Videos behalten; Videodaten werden nicht in Room gespeichert. Diese Informationen dienen den Mess-, Verlaufs-, Vergleichs- und Exportfunktionen der App.

### Kamera und Videos

Der Kamerazugriff wird nur verwendet, wenn Sie eine Videoaufnahme starten. Ein mit OpenJump aufgenommenes Video wird in der gemeinsamen Sammlung `Movies/OpenJump` veröffentlicht. Andere Apps auf dem Gerät, einschließlich Galerie- oder Cloud-Synchronisierungs-Apps, können diese Dateien gemäß ihren eigenen Datenschutzrichtlinien ansehen oder synchronisieren.

Importierte Videos werden vom von Ihnen ausgewählten Speicherort gelesen und nicht in die OpenJump-Datenbank kopiert. Das Löschen eines Ergebnisses löscht das zugehörige Video nicht; gemeinsam gespeicherte Videos können separat über die Galerie oder den Dateimanager gelöscht werden. Unter Android 8 und 9 fordert OpenJump Schreibzugriff nur an, um eine Aufnahme zu veröffentlichen. Auf neueren Android-Versionen wird der begrenzte Medienspeicher von Android verwendet. OpenJump fordert keine Mikrofon-, Standort- oder Internetberechtigung an.

### Teilen und Exportieren

OpenJump verwendet keine automatische Analyse/Telemetrie und kein Backend. Sie können ausdrücklich eine Aufnahme in `Movies/OpenJump` speichern, JSON/CSV oder ein Trajektorienvideo exportieren oder Backup v1 erstellen. Dieses Backup ist weder verschlüsselt noch authentifiziert; der gewählte Dokumentanbieter kann es synchronisieren. Ziel-Apps/-Dienste verarbeiten ihre Kopien gemäß den eigenen Richtlinien.

„Über OpenJump“ kann einen bearbeitbaren E-Mail-Entwurf an **openjump.app@gmail.com** öffnen. Vorausgefüllt sind App-Version/-Code, Android-Version/API, Hersteller und Modell sowie leere Felder für Problembeschreibung und Reproduktionsschritte. Geräte-/Nutzer-IDs, Kontodaten, Video, Messung/Ergebnis, Dateipfad oder Video-URI werden nicht automatisch angehängt. OpenJump sendet den Entwurf nicht: Sie prüfen/bearbeiten ihn und entscheiden in einer E-Mail-App, ob Sie ihn senden. Sie können persönliche oder sensible Angaben hinzufügen. Die E-Mail-App/der Anbieter verarbeitet die Nachricht; OpenJump kontrolliert oder garantiert deren Verarbeitung, Aufbewahrung oder Löschung nicht. „Über OpenJump“ bietet außerdem auf Ihre Initiative an, dieselben oben beschriebenen grundlegenden Installations-/Gerätediagnosen in die Zwischenablage zu kopieren oder über die Android-Auswahl an eine von Ihnen gewählte App weiterzugeben.

OpenJump verkauft keine personenbezogenen Daten und verwendet sie nicht für Werbung, Profilbildung oder automatische Analysen.

### Aufbewahrung, Löschung und Sicherung

Lokale Daten bleiben auf dem Gerät, bis Sie ein gespeichertes Ergebnis löschen, die OpenJump-App-Daten entfernen oder die App deinstallieren. Beim Archivieren eines Athleten oder einer Gruppe bleibt der Messverlauf erhalten. Beim Löschen eines Ergebnisses werden der Room-Datensatz und die zugehörigen Analyseobjekte entfernt, nicht jedoch ein separat gespeichertes Video oder ein Export.

Die Android-Cloudsicherung und die Geräteübertragung sind für OpenJump deaktiviert. Durch Deinstallation oder Löschen der App-Daten kann die lokale Datenbank daher dauerhaft entfernt werden. Von Nutzern erstellte Exporte und Videos im gemeinsamen Speicher müssen separat gelöscht werden.

### Manuelles Backup

Die Einstellungen bieten außerdem ein separates, vom Nutzer gestartetes
manuelles Backup. Sein JSON v1 wird aus Room 10 erzeugt und enthält den
vollständigen Room-10-Datenbestand; es akzeptiert auch v1-Backups aus Room 9
und Room 10. Es enthält Namen, Daten,
Geschlecht, Notizen, anthropometrische Daten, Gruppen, Tests, Ergebnisse,
Ereignisse, Kalibrierungen und Trajektorien-Samples. Ausgeschlossen sind Videos,
Video-Links/URIs, Cache, Einstellungen und temporäre Sitzungen. Die Wiederherstellung ersetzt nach Bestätigung alle lokalen
Daten in einer atomaren Transaktion mit Rollback; Daten werden nicht zusammengeführt.
Die Datei ist weder verschlüsselt noch authentifiziert und der gewählte Anbieter
kann sie synchronisieren. Android-Cloud-Backup und Geräteübertragung bleiben
deaktiviert.

### Kinder und Gesundheitsinformationen

OpenJump überträgt Kinder- oder Gesundheitsdaten nicht automatisch. Wenn Sie solche Angaben freiwillig in eine Support-E-Mail aufnehmen, wird diese über Ihre E-Mail-App/Ihren Anbieter an die Support-Adresse gesendet. Senden Sie Daten anderer Personen nur mit geeigneter Rechtsgrundlage und Einwilligung. OpenJump unterstützt sportliche Leistung und ist kein medizinisches, klinisches oder diagnostisches Gerät.

### Sicherheit, Open Source und Kontakt

Daten sind durch die Android-App-Sandbox geschützt, solange sie im privaten Speicher von OpenJump liegen. Keine rein lokale Anwendung kann absolute Sicherheit garantieren, insbesondere nicht auf einem manipulierten Gerät. Wesentliche Änderungen werden in dieser Richtlinie und in der App veröffentlicht.

Der Quellcode von OpenJump wird unter der Lizenz **GPL-3.0-or-later** auf https://github.com/cortig00/OpenJump veröffentlicht. Nutzerdatenbanken, Videos, Exporte, Signaturschlüssel und lokale Konfigurationen gehören nicht zum Repository.

Fragen oder möchten Sie die Löschung einer von Ihnen gesendeten Support-Nachricht anfragen? Kontaktieren Sie **openjump.app@gmail.com**. Eine bestimmte Aufbewahrungsdauer oder automatische Löschung wird nicht zugesagt.

## Italiano

OpenJump elabora le misurazioni sul dispositivo, non ha account né backend OpenJump e non richiede il permesso Internet. Non usa analisi o telemetria automatiche. Questo non significa che le copie non escano mai dal dispositivo: condivisioni, esportazioni, video in memoria condivisa ed e-mail di supporto avviati dall’utente sono descritti di seguito.

### Dati salvati sul dispositivo

OpenJump salva le informazioni registrate inserite o create dall’utente nel database Android Room locale, nell’archiviazione privata dell’app. Può includere nomi di atleti e, se forniti, data di nascita, sesso, note, peso, altezza, appartenenza a gruppi, sessioni di test, misurazioni di salto, sessioni Encoder, eventi marcati, metriche, dati di calibrazione e campioni di analisi della traiettoria. Un risultato salvato può conservare solo l’URI di riferimento del video selezionato; i byte video non sono memorizzati in Room.

Queste informazioni servono esclusivamente per le funzioni di misurazione, cronologia, confronto ed esportazione dell'app.

### Fotocamera e video

La fotocamera viene utilizzata solo quando l'utente sceglie di registrare un video. Un video registrato da OpenJump viene pubblicato nella raccolta condivisa `Movies/OpenJump` del dispositivo, così resta disponibile per l'utente. Altre applicazioni sul dispositivo, incluse gallerie o servizi di sincronizzazione cloud, possono visualizzare o sincronizzare i file di quella raccolta condivisa secondo le proprie informative sulla privacy.

I video selezionati per l'importazione vengono letti dalla posizione scelta dall'utente e non vengono copiati nel database di OpenJump. Eliminare un risultato di OpenJump non elimina il video importato o registrato associato. I video condivisi possono essere eliminati separatamente con la galleria o il gestore file del dispositivo.

Su Android 8 e 9, OpenJump richiede l'accesso di scrittura legacy solo per pubblicare una registrazione in `Movies/OpenJump`. Sulle versioni più recenti di Android utilizza l'archiviazione multimediale limitata di Android. OpenJump non richiede i permessi di microfono, posizione o Internet.

### Condivisione ed esportazione

OpenJump non usa analisi/telemetria automatiche né un backend. Puoi salvare esplicitamente una registrazione in `Movies/OpenJump`, esportare JSON/CSV o un video della traiettoria, oppure creare il backup manuale v1. Il backup non è cifrato né autenticato; il provider di documenti scelto può sincronizzarlo. App/servizi di destinazione gestiscono le copie secondo le proprie informative.

About può aprire una bozza e-mail modificabile per **openjump.app@gmail.com**. Precompila versione/codice dell’app, versione/API Android, produttore e modello, oltre a campi vuoti per descrivere il problema e i passaggi per riprodurlo. Non allega automaticamente ID univoci di dispositivo/utente, dati account, video, misurazioni/risultati, percorsi o URI video. OpenJump non invia la bozza: la rivedi/modifichi e scegli se inviarla nell’app e-mail. Puoi aggiungere dati personali o sensibili. L’app/provider e-mail gestisce il messaggio inviato; OpenJump non controlla né garantisce trattamento, conservazione o cancellazione. About offre anche, su tua iniziativa, di copiare negli appunti gli stessi dati diagnostici di base dell’installazione/dispositivo descritti sopra o condividerli con un’app scelta tramite il selettore Android.

OpenJump non vende dati personali né li usa per pubblicità, profilazione o analisi automatiche.

### Conservazione, eliminazione e backup

I dati locali restano sul dispositivo finché l'utente non elimina un singolo risultato salvato, cancella i dati dell'app di OpenJump o disinstalla l'app. Archiviare un atleta o un gruppo ne conserva le misurazioni storiche. Eliminare un risultato salvato rimuove il suo record Room e gli elementi di analisi associati, ma non un video o un'esportazione archiviati separatamente.

Il backup cloud Android e il trasferimento tra dispositivi sono disattivati per OpenJump. Disinstallare l'app o cancellarne i dati può quindi rimuovere definitivamente il database locale. Le esportazioni e i video creati dall'utente nell'archiviazione condivisa devono essere eliminati separatamente.

### Backup manuale

La schermata Impostazioni offre inoltre un backup manuale separato, avviato dall'utente. Il suo JSON v1 generato da Room 10 contiene il grafo dati completo di Room 10; resta compatibile con i backup v1 di Room 9 e Room 10. Include nomi, date, sesso, note, antropometria, gruppi, test, risultati, eventi marcati, calibrazioni e campioni di traiettoria. Esclude video, link/URI video, cache, preferenze e sessioni temporanee. Il ripristino è una sostituzione totale esplicita con transazione atomica e rollback; non unisce i dati. Il file non è cifrato né autenticato, quindi il provider di documenti scelto potrebbe sincronizzarlo. Il backup cloud Android e il trasferimento tra dispositivi restano disattivati.

### Minori e informazioni sulla salute

OpenJump non trasmette automaticamente informazioni su minori o sulla salute. Se scegli di includerle in un’e-mail di supporto, il messaggio viene inviato tramite l’app/provider e-mail all’indirizzo di supporto. Non inviare informazioni di altre persone senza un’adeguata base giuridica e consenso. OpenJump supporta le prestazioni sportive; non è un dispositivo medico, clinico o diagnostico.

### Sicurezza e modifiche

I dati sono protetti dalla sandbox Android finché restano nell'archiviazione privata di OpenJump. Nessuna applicazione solo locale può garantire una sicurezza assoluta, in particolare su un dispositivo compromesso. Le modifiche rilevanti a questa informativa saranno riflesse in questo documento e nell'informativa mostrata all'interno dell'app.

### Codice aperto

Il codice sorgente di OpenJump è pubblicato su https://github.com/cortig00/OpenJump con licenza **GPL-3.0-or-later**. Chiunque può esaminarlo, modificarlo e ridistribuirlo secondo i termini di tale licenza. Le versioni ufficiali sono distribuite tramite Google Play e i canali indicati dai manutentori del repository.

La pubblicazione del codice sorgente non divulga informazioni utente: database, video, esportazioni, chiavi di firma e configurazione locale di OpenJump non fanno parte del repository sorgente.

### Contatto

Hai domande o vuoi chiedere la cancellazione di un messaggio di supporto che hai scelto di inviare? Contatta **openjump.app@gmail.com**. Non viene promessa una durata specifica di conservazione né la cancellazione automatica.

## Português (Brasil)

O OpenJump processa medições no dispositivo, não tem conta nem backend próprio e não solicita permissão de Internet. Não usa análise ou telemetria automática. Isso não significa que cópias nunca saiam do dispositivo: compartilhamentos, exportações, vídeos em armazenamento compartilhado e e-mails de suporte iniciados pelo usuário estão descritos abaixo.

### Dados armazenados no dispositivo

O OpenJump armazena informações salvas inseridas ou criadas pelo usuário no banco de dados Android Room local, no armazenamento privado do app. Isso pode incluir nomes de atletas e, quando informados, data de nascimento, sexo, notas, peso, altura, participação em grupos, sessões de testes, medições de salto, sessões do Encoder, eventos marcados, métricas, dados de calibração e amostras de análise de trajetória. Um resultado salvo pode manter apenas a URI de referência do vídeo selecionado; os dados do vídeo não são armazenados no Room.

Essas informações são usadas apenas para fornecer os recursos de medição, histórico, comparação e exportação do aplicativo.

### Câmera e vídeos

O acesso à câmera é usado apenas quando o usuário escolhe gravar um vídeo. Um vídeo gravado pelo OpenJump é publicado na coleção compartilhada `Movies/OpenJump` do dispositivo, para continuar disponível ao usuário. Outros aplicativos no dispositivo, incluindo galerias ou serviços de sincronização na nuvem, podem visualizar ou sincronizar os arquivos dessa coleção compartilhada de acordo com suas próprias políticas de privacidade.

Os vídeos selecionados para importação são lidos do local escolhido pelo usuário e não são copiados para o banco de dados do OpenJump. Excluir um resultado do OpenJump não exclui o vídeo importado ou gravado associado. Os vídeos compartilhados podem ser excluídos separadamente pela galeria ou pelo gerenciador de arquivos do dispositivo.

No Android 8 e 9, o OpenJump solicita acesso de escrita legado apenas para publicar uma gravação em `Movies/OpenJump`. Nas versões mais recentes do Android, usa o armazenamento de mídia com escopo do Android. O OpenJump não solicita permissão de microfone, localização ou Internet.

### Compartilhamento e exportação

O OpenJump não usa análise/telemetria automática nem backend. Você pode salvar explicitamente uma gravação em `Movies/OpenJump`, exportar JSON/CSV ou vídeo de trajetória, ou criar o backup manual v1. Esse backup não é criptografado nem autenticado; o provedor de documentos escolhido pode sincronizá-lo. Os apps/serviços de destino tratam suas cópias conforme as próprias políticas.

Sobre pode abrir um rascunho de e-mail editável para **openjump.app@gmail.com**. Ele preenche versão/código do app, versão/API do Android, fabricante e modelo, além de campos vazios para descrever o problema e os passos para reproduzi-lo. Não anexa automaticamente IDs únicos de dispositivo/usuário, dados de conta, vídeo, medição/resultado, caminho ou URI de vídeo. O OpenJump não envia o rascunho: você o revisa/edita e decide se o envia no app de e-mail. Você pode incluir dados pessoais ou sensíveis. O app/provedor de e-mail trata a mensagem enviada; o OpenJump não controla nem garante suas práticas de tratamento, retenção ou exclusão. Sobre também oferece, por sua iniciativa, copiar para a área de transferência os mesmos diagnósticos básicos de instalação/dispositivo descritos acima ou compartilhá-los com um app escolhido pelo seletor do Android.

O OpenJump não vende dados pessoais nem os usa para publicidade, perfil ou análise automática.

### Retenção, exclusão e backup

Os dados locais permanecem no dispositivo até que o usuário exclua um resultado salvo individual, limpe os dados do aplicativo OpenJump ou desinstale o aplicativo. Arquivar um atleta ou grupo preserva suas medições históricas. Excluir um resultado salvo remove seu registro Room e os elementos de análise associados, mas não um vídeo ou uma exportação armazenados separadamente.

O backup em nuvem do Android e a transferência entre dispositivos estão desativados para o OpenJump. Desinstalar o aplicativo ou limpar seus dados pode, portanto, remover permanentemente o banco de dados local. Exportações e vídeos criados pelo usuário no armazenamento compartilhado devem ser excluídos separadamente.

### Backup manual

A tela de Configurações também oferece um backup manual separado, iniciado pelo usuário. Seu JSON v1 emitido do Room 10 contém o grafo completo de dados do Room 10; permanece compatível com backups v1 do Room 9 e do Room 10. Inclui nomes, datas, sexo, notas, antropometria, grupos, testes, resultados, eventos marcados, calibrações e amostras de trajetória. Exclui vídeos, links/URIs de vídeo, cache, preferências e sessões temporárias. A restauração é uma substituição total explícita com transação atômica e reversão segura; não mescla dados. O arquivo não é criptografado nem autenticado, portanto o provedor de documentos escolhido pode sincronizá-lo. O backup em nuvem do Android e a transferência entre dispositivos permanecem desativados.

### Crianças e informações de saúde

O OpenJump não transmite automaticamente informações sobre crianças ou saúde. Se você decidir incluí-las em um e-mail de suporte, a mensagem será enviada pelo seu app/provedor de e-mail ao endereço de suporte. Não envie informações de outra pessoa sem base legal e consentimento adequados. O OpenJump auxilia o desempenho esportivo; não é um dispositivo médico, clínico ou de diagnóstico.

### Segurança e alterações

Os dados são protegidos pela sandbox do Android enquanto permanecem no armazenamento privado do OpenJump. Nenhum aplicativo exclusivamente local pode garantir segurança absoluta, especialmente em um dispositivo comprometido. Alterações relevantes nesta política serão refletidas neste documento e na política exibida dentro do aplicativo.

### Código aberto

O código-fonte do OpenJump está publicado em https://github.com/cortig00/OpenJump sob a licença **GPL-3.0-or-later**. Qualquer pessoa pode inspecioná-lo, modificá-lo e redistribuí-lo conforme os termos dessa licença. As versões oficiais são distribuídas pelo Google Play e pelos canais indicados pelos mantenedores do repositório.

A publicação do código-fonte não divulga informações do usuário: banco de dados, vídeos, exportações, chaves de assinatura e configuração local do OpenJump não fazem parte do repositório de código.

### Contato

Tem dúvidas ou quer solicitar a exclusão de uma mensagem de suporte que decidiu enviar? Escreva para **openjump.app@gmail.com**. Não é prometido um prazo específico de retenção nem a exclusão automática de mensagens.

## Português (Portugal)

O OpenJump processa medições no dispositivo, não tem conta nem backend próprio e não pede autorização de Internet. Não usa análise ou telemetria automática. Isto não significa que as cópias nunca saiam do dispositivo: as partilhas, exportações, vídeos em armazenamento partilhado e e-mails de suporte iniciados pelo utilizador são descritos abaixo.

### Dados guardados no dispositivo

O OpenJump guarda as informações registadas introduzidas ou criadas pelo utilizador na base de dados Android Room local, no armazenamento privado da aplicação. Isto pode incluir nomes de atletas e, quando fornecidos, data de nascimento, sexo, notas, peso, altura, pertença a grupos, sessões de testes, medições de salto, sessões do Encoder, eventos marcados, métricas, dados de calibração e amostras de análise de trajetória. Um resultado guardado pode manter apenas o URI de referência do vídeo selecionado; os dados do vídeo não são armazenados no Room.

Estas informações são usadas apenas para fornecer as funcionalidades de medição, histórico, comparação e exportação da aplicação.

### Câmara e vídeos

O acesso à câmara é usado apenas quando o utilizador escolhe gravar um vídeo. Um vídeo gravado pelo OpenJump é publicado na coleção partilhada `Movies/OpenJump` do dispositivo, para continuar disponível para o utilizador. Outras aplicações no dispositivo, incluindo galerias ou serviços de sincronização na nuvem, podem ver ou sincronizar os ficheiros dessa coleção partilhada de acordo com as suas próprias políticas de privacidade.

Os vídeos selecionados para importação são lidos a partir do local escolhido pelo utilizador e não são copiados para a base de dados do OpenJump. Eliminar um resultado do OpenJump não elimina o vídeo importado ou gravado associado. Os vídeos partilhados podem ser eliminados separadamente através da galeria ou do gestor de ficheiros do dispositivo.

No Android 8 e 9, o OpenJump pede acesso de escrita legado apenas para publicar uma gravação em `Movies/OpenJump`. Nas versões mais recentes do Android, utiliza o armazenamento multimédia com âmbito do Android. O OpenJump não pede permissão de microfone, localização ou Internet.

### Partilha e exportação

O OpenJump não usa análise/telemetria automática nem backend. Pode guardar explicitamente uma gravação em `Movies/OpenJump`, exportar JSON/CSV ou um vídeo de trajetória, ou criar a cópia manual v1. Esta cópia não é cifrada nem autenticada; o fornecedor de documentos escolhido pode sincronizá-la. As aplicações/serviços de destino tratam as suas cópias segundo as próprias políticas.

Sobre pode abrir um rascunho de e-mail editável para **openjump.app@gmail.com**. Inclui versão/código da aplicação, versão/API Android, fabricante e modelo, além de campos vazios para descrever o problema e os passos de reprodução. Não anexa automaticamente IDs únicos do dispositivo/utilizador, dados de conta, vídeo, medição/resultado, caminho ou URI de vídeo. O OpenJump não envia o rascunho: reveja/edite-o e decida se o envia pela aplicação de e-mail. Pode acrescentar dados pessoais ou sensíveis. A aplicação/fornecedor de e-mail trata a mensagem enviada; o OpenJump não controla nem garante as práticas de tratamento, conservação ou eliminação. Sobre também permite, por sua iniciativa, copiar para a área de transferência os mesmos diagnósticos básicos de instalação/dispositivo descritos acima ou partilhá-los com uma aplicação escolhida no seletor do Android.

O OpenJump não vende dados pessoais nem os utiliza para publicidade, perfis ou análise automática.

### Conservação, eliminação e cópia de segurança

Os dados locais permanecem no dispositivo até o utilizador eliminar um resultado guardado individual, limpar os dados da aplicação OpenJump ou desinstalar a aplicação. Arquivar um atleta ou grupo preserva as respetivas medições históricas. Eliminar um resultado guardado remove o seu registo Room e os elementos de análise associados, mas não um vídeo ou uma exportação guardados separadamente.

A cópia de segurança na nuvem do Android e a transferência entre dispositivos estão desativadas para o OpenJump. Desinstalar a aplicação ou limpar os seus dados pode, por isso, remover permanentemente a base de dados local. As exportações e os vídeos criados pelo utilizador no armazenamento partilhado têm de ser eliminados separadamente.

### Cópia de segurança manual

O ecrã de Definições oferece também uma cópia de segurança manual separada, iniciada pelo utilizador. O seu JSON v1 emitido a partir do Room 10 contém o grafo completo de dados do Room 10; permanece compatível com cópias v1 do Room 9 e do Room 10. Inclui nomes, datas, sexo, notas, antropometria, grupos, testes, resultados, eventos marcados, calibrações e amostras de trajetória. Exclui vídeos, ligações/URIs de vídeo, cache, preferências e sessões temporárias. O restauro é uma substituição total explícita com transação atómica e reversão segura; não combina dados. O ficheiro não é cifrado nem autenticado, pelo que o fornecedor de documentos escolhido pode sincronizá-lo. A cópia de segurança na nuvem do Android e a transferência entre dispositivos permanecem desativadas.

### Crianças e informações de saúde

O OpenJump não transmite automaticamente informações sobre crianças ou saúde. Se optar por incluí-las num e-mail de suporte, a mensagem é enviada através da sua aplicação/fornecedor de e-mail para o endereço de suporte. Não envie informações de outra pessoa sem base legal e consentimento adequados. O OpenJump apoia o desempenho desportivo; não é um dispositivo médico, clínico ou de diagnóstico.

### Segurança e alterações

Os dados são protegidos pela sandbox do Android enquanto permanecem no armazenamento privado do OpenJump. Nenhuma aplicação exclusivamente local pode garantir segurança absoluta, em especial num dispositivo comprometido. Alterações relevantes a esta política serão refletidas neste documento e na política apresentada dentro da aplicação.

### Código aberto

O código-fonte do OpenJump está publicado em https://github.com/cortig00/OpenJump sob a licença **GPL-3.0-or-later**. Qualquer pessoa pode inspecioná-lo, modificá-lo e redistribuí-lo nos termos dessa licença. As versões oficiais são distribuídas através do Google Play e dos canais indicados pelos responsáveis do repositório.

A publicação do código-fonte não divulga informações do utilizador: base de dados, vídeos, exportações, chaves de assinatura e configuração local do OpenJump não fazem parte do repositório de código.

### Contacto

Tem dúvidas ou pretende pedir a eliminação de uma mensagem de suporte que decidiu enviar? Contacte **openjump.app@gmail.com**. Não é prometido um prazo específico de conservação nem a eliminação automática.

## Türkçe

OpenJump ölçümleri cihazda işler; hesabı veya OpenJump arka ucu yoktur ve İnternet izni istemez. Otomatik analiz veya telemetri kullanmaz. Bu, kopyaların cihazdan hiç çıkmadığı anlamına gelmez: kullanıcının başlattığı paylaşım, dışa aktarma, paylaşılan video depolama ve destek e-postaları aşağıda açıklanmıştır.

### Cihazda saklanan veriler

OpenJump, kaydedilmiş kullanıcı girişlerini veya oluşturulan bilgileri uygulamanın özel depolamasındaki yerel Android Room veritabanında saklar. Bunlar; sporcu adlarını ve sağlanmışsa doğum tarihi, cinsiyet, notlar, ağırlık, boy, grup üyeliği, test oturumları, sıçrama ölçümleri, Encoder oturumları, işaretlenmiş olaylar, metrikler, kalibrasyon verileri ve yörünge analizi örneklerini içerebilir. Kaydedilmiş bir sonuç seçilen videonun yalnızca başvuru URI’sini tutabilir; video verileri Room’da saklanmaz.

Bu bilgiler yalnızca uygulamanın ölçüm, geçmiş, karşılaştırma ve dışa aktarma özelliklerini sağlamak için kullanılır.

### Kamera ve videolar

Kamera erişimi yalnızca kullanıcı video kaydetmeyi seçtiğinde kullanılır. OpenJump ile kaydedilen bir video, kullanıcı için erişilebilir kalması amacıyla cihazın paylaşılan `Movies/OpenJump` koleksiyonunda yayımlanır. Galeriler veya bulut senkronizasyon uygulamaları dahil olmak üzere cihazdaki diğer uygulamalar, paylaşılan koleksiyondaki dosyaları kendi gizlilik politikaları kapsamında görüntüleyebilir veya senkronize edebilir.

İçe aktarmak için seçilen videolar, kullanıcının seçtiği konumdan okunur ve OpenJump veritabanına kopyalanmaz. Bir OpenJump sonucunu silmek, ilişkili içe aktarılmış veya kaydedilmiş videoyu silmez. Paylaşılan videolar, cihazın galerisi veya dosya yöneticisi ile ayrıca silinebilir.

Android 8 ve 9'da OpenJump, yalnızca bir kaydı `Movies/OpenJump` içinde yayımlamak için eski depolama yazma erişimi ister. Daha yeni Android sürümlerinde Android'in kapsamlı medya depolamasını kullanır. OpenJump mikrofon, konum veya İnternet izni istemez.

### Paylaşma ve dışa aktarma

OpenJump otomatik analiz/telemetri veya arka uç kullanmaz. Bir kaydı `Movies/OpenJump` içine açıkça kaydedebilir, JSON/CSV veya yörünge videosu dışa aktarabilir ya da manuel yedek v1 oluşturabilirsiniz. Bu yedek şifrelenmez/doğrulanmaz; seçilen belge sağlayıcısı eşitleyebilir. Hedef uygulama/hizmet kopyaları kendi politikalarına göre işler.

Hakkında ekranı **openjump.app@gmail.com** adresine düzenlenebilir bir e-posta taslağı açabilir. Uygulama sürümü/kodu, Android sürümü/API, üretici ve model önceden doldurulur; sorun açıklaması ve yeniden oluşturma adımları için boş alanlar vardır. Benzersiz cihaz/kullanıcı kimliği, hesap verisi, video, ölçüm/sonuç, dosya yolu veya video URI’si otomatik eklenmez. Taslağı OpenJump göndermez: inceler/düzenler ve e-posta uygulamasında gönderip göndermemeye siz karar verirsiniz. Kişisel veya hassas bilgiler ekleyebilirsiniz. Gönderilen iletiyi e-posta uygulaması/sağlayıcısı işler; OpenJump bu işlemi, saklamayı veya silmeyi kontrol etmez ya da garanti etmez. Hakkında ayrıca, seçiminizle yukarıda açıklanan aynı temel kurulum/cihaz tanı bilgilerini panoya kopyalama veya Android seçicisi aracılığıyla seçtiğiniz bir uygulamayla paylaşma olanağı sunar.

OpenJump kişisel verileri satmaz ve bunları reklam, profilleme veya otomatik analiz için kullanmaz.

### Saklama, silme ve yedekleme

Yerel veriler, kullanıcı kayıtlı bir sonucu silene, OpenJump uygulama verilerini temizleyene veya uygulamayı kaldırana kadar cihazda kalır. Bir sporcuyu veya grubu arşivlemek, geçmiş ölçümlerini korur. Kayıtlı bir sonucu silmek, Room kaydını ve ilişkili analiz öğelerini kaldırır; ancak ayrıca saklanan bir videoyu veya dışa aktarmayı kaldırmaz.

OpenJump için Android bulut yedekleme ve cihazlar arası aktarım devre dışıdır. Bu nedenle uygulamayı kaldırmak veya verilerini temizlemek yerel veritabanını kalıcı olarak silebilir. Paylaşılan depolamadaki kullanıcı tarafından oluşturulan dışa aktarımlar ve videolar ayrıca silinmelidir.

### Manuel yedekleme

Ayarlar ekranı ayrıca kullanıcı tarafından başlatılan ayrı bir manuel yedekleme sunar. Room 10'den üretilen sürümlü JSON v1'i, Room 10 veri grafının tamamını içerir; Room 9 ve Room 10 kaynaklı v1 yedeklemeleriyle uyumlu kalır. Adları, tarihleri, cinsiyeti, notları, antropometriyi, grupları, testleri, sonuçları, işaretlenmiş olayları, kalibrasyonları ve yörünge örneklerini içerir. Videoları, video bağlantılarını/URI'lerini, önbelleği, tercihleri ve geçici oturumları hariç tutar. Geri yükleme; verileri birleştirmeden, atomik geri almaya güvenli işlemle yapılan açık bir toplam değiştirmedir. Dosya şifrelenmez veya kimliği doğrulanmaz, bu nedenle seçilen belge sağlayıcı onu senkronize edebilir. Android bulut yedekleme ve cihaz aktarımı devre dışı kalır.

### Çocuklar ve sağlık bilgileri

OpenJump çocuklara veya sağlığa ilişkin bilgileri otomatik olarak iletmez. Bu tür bilgileri bir destek e-postasına eklemeyi seçerseniz ileti, e-posta uygulamanız/sağlayıcınız üzerinden destek adresine gönderilir. Uygun yasal dayanak ve rıza olmadan başka bir kişinin bilgilerini göndermeyin. OpenJump spor performansına yardımcı olur; tıbbi, klinik veya tanı cihazı değildir.

### Güvenlik ve değişiklikler

Veriler, OpenJump'un özel depolamasında kaldığı sürece Android uygulama korumalı alanıyla korunur. Yalnızca yerel çalışan hiçbir uygulama, özellikle güvenliği ihlal edilmiş bir cihazda mutlak güvenlik garanti edemez. Bu politikadaki önemli değişiklikler bu belgeye ve uygulama içinde gösterilen politikaya yansıtılacaktır.

### Açık kaynak

OpenJump kaynak kodu, **GPL-3.0-or-later** lisansı altında https://github.com/cortig00/OpenJump adresinde yayımlanır. Herkes bu lisansın koşulları kapsamında kodu inceleyebilir, değiştirebilir ve yeniden dağıtabilir. Resmî sürümler Google Play ve depo bakımcıları tarafından belirtilen kanallar üzerinden dağıtılır.

Kaynak kodunu yayımlamak kullanıcı bilgilerini açıklamaz: OpenJump veritabanı, videolar, dışa aktarımlar, imza anahtarları ve yerel yapılandırma kaynak depo parçası değildir.

### İletişim

Sorunuz mu var veya kendi isteğinizle gönderdiğiniz bir destek iletisinin silinmesini mi talep etmek istiyorsunuz? **openjump.app@gmail.com** adresinden bize yazın. Belirli bir saklama süresi veya otomatik silme sözü verilmez.
