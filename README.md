# Busdrift Navigator v5 — ruter via MySQL

Chaufførens app læser tur og beregnede vejforløb fra MySQL gennem `server/navigator-v5.php`. Windows-rutearbejderen henter nye rutejob via HTTPS, beregner på Windows-pc'ens Nominatim/OSRM og sender ruten tilbage til PHP, som gemmer den i MySQL. Der kræves ingen indgående port til Windows-pc'en og ingen databaseadgang i Android-appen.

Se [Windows-opsætning](navigator-worker/README.md) for installation.

# Busdrift Navigator til chauffører

Android app og PHP 8.0 API til eksisterende Busdrift på `https://minside.hotservice.dk/`. Appen læser **ikke MySQL direkte**: `server/navigator-v5.php` bruger den allerede gemte `data/settings.php` på webserveren. Filen `settings.php` må ikke kopieres til appen eller GitHub.

## Kørsel på en arbejdsdag

1. Administrator tildeler én eller flere ture til chaufføren, vælger garage og bus, og sørger for, at garage og turens stop har adresser. Chaufføren får sit eksisterende chaufførnummer og PIN under **Chaufførportal**.
2. Chaufføren logger ind i appen. Dagens ture vises i tidsorden. Appen laver en stopliste: **garage → alle kundestop → tilbage til garage**. Hvis dagens ture bruger forskellige garager eller busser, indsættes et returbesøg og en ny start ved garagen, når garage eller bus skifter.
3. Chaufføren trykker **Start kørslen fra garagen**. Appens egen kortskærm viser rute, GPS-position, vejvisning, resterende kilometer og nedtælling i timer:minutter:sekunder til næste stop. Ruten genberegnes cirka hvert 25. sekund under kørsel. Ved hvert stop markeres **Jeg er ankommet**, hvorefter næste stop åbnes. GPS deles under kørslen, hvis chaufføren giver placeringstilladelse.
4. Chaufføren markerer ankomsten til garagen og trykker **Afslut dagen og log ud**. Serveren registrerer afslutning og lukker den aktuelle telefons adgangsnøgle. En tidlig manuel logout afslutter ikke dagen.

## Installation på PHP-server

Upload **kun** `server/navigator-v5.php` til den mappe på PHP-serveren, der indeholder `api.php`, fx roden på `minside.hotservice.dk`. Den forventer at læse `data/settings.php` under **samme mappe**. Hvis siden ligger i en undermappe, tilpas serveradressen på login-skærmen til undermappen. Brug ikke URL'en til `data/settings.php` i appen.

Ved første kald oprettes tabellerne `navigator_devices`, `navigator_login_limits`, `navigator_days`, `navigator_waypoints` og `navigator_positions` i samme MySQL-database. PHP kræver `pdo_mysql` og MySQL-brugerens rettighed til at oprette tabeller. Den eksisterende databaseopsætning, ture, chauffører, garager og busser ændres ikke. HTTPS er påkrævet for chaufførlogin fra appen.

Brug administratordelen i Busdrift til at oprette chaufførens nummer og PIN. Der skal allerede være poster i `records` med `kind='driver_access'` og chaufførens `driverId`. **Kopier aldrig PIN eller databaseadgang til GitHub.**

## Byg APK med GitHub

Projektet kan ligge i [myhomerico-stack/Busdrift](https://github.com/myhomerico-stack/Busdrift). GitHub Actions i `.github/workflows/build-android.yml` bygger en debug APK ved push til `main`. Åbn arkivets **Actions → Byg Navigator APK → seneste kørsel → Artifacts**, og hent `Busdrift-Navigator-debug-APK`. En debug APK er til intern afprøvning, ikke en signeret Play Store-udgivelse.

Alternativt: åbn `android` i Android Studio (JDK 17, Android SDK 35, Gradle 8.9), og byg `:app:assembleDebug`. Koden er skrevet uden eksterne Android-biblioteker.

## Kort og ruteopsætning

`navigator-v5.php` bruger kun MySQL fra `data/settings.php` til turdata, rutejob og færdige ruter. Windows-rutearbejderen henter job udgående via HTTPS, bruger Windows-pc'ens Nominatim og OSRM og sender vejforløbet tilbage til MySQL via PHP. De gamle OSRM-adresser under Database behøves ikke til chaufførappens ruter. Kortfliser hentes fra OpenStreetMap over HTTPS og caches på telefonen. Installer begge dele før brug.

## Grænser og afprøvning

- Telefonen skal kunne nå `https://minside.hotservice.dk/navigator-v5.php` via mobilnettet. Appen fungerer ikke ude på ruten, hvis PHP kun er tilgængelig på et lokalt 192.168-netværk. Kontroller HTTPS og PHP, før den tages i brug.
- OSRM-profilen `driving` tager ikke nødvendigvis højde for bussens højde, bredde, vægt eller lokale restriktioner. Chaufføren skal følge skiltning og kontrollere ruten for busrestriktioner, inden turen køres.
- En aktiv internetforbindelse er nødvendig for at hente og registrere stop. Fejler registreringen, forbliver stoppet aktivt, så chaufføren kan prøve igen.
- Hvis en telefons adgang skal spærres, kan en administrator sætte dens række i `navigator_devices.revoked_at`. Logout spærrer automatisk den aktuelle nøgle.

Afprøv med en testchauffør, en garage med adresse og en tur med to stop: log ind, start dagen, kontrollér kort, vejvisning og nedtælling, kør stop i rækkefølge, markér garagereturen, afslut og kontrollér ny login er påkrævet. Projektet er ikke verificeret mod den aktive server.

## Tjek at den nye PHP-fil bruges

Åbn `https://minside.hotservice.dk/navigator-v5.php?action=version` i en browser. Svaret skal være JSON med `"apiVersion":5`. Hvis du ser en HTML-fejlside, ligger filen ikke i samme offentlige mappe som `api.php`. Denne kontrol læser ikke databaseindstillinger eller chaufførdata. Åbn derefter den nye APK. Den gamle `navigator-api.php` kan stå urørt.

Kortet har knapper til +, − og automatisk zoom centreret på GPS med cirka 200 meter til kanten. Hvis Windows-pc'en er slukket, bliver ruten stående som afventende. Kontrollér først `navigator-v5.php?action=version`; svaret skal have `apiVersion:5` og `routeSource:"mysql"`.
