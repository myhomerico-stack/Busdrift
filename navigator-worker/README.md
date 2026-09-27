# Busdrift Navigator: ruter via MySQL

Denne Windows-rutearbejder bruger de Nominatim- og OSRM-tjenester, som allerede kører på Windows-pc'en. Den **henter rutejob og sender færdige ruter udgående via HTTPS** til Busdrifts PHP-server. PHP gemmer ruterne i den samme Simply MySQL-database som turene. Windows-pc'en behøver hverken indgående port fra Synology eller MySQL-adgang. Android-appen får heller ikke databaseadgang.

## Installation

1. Upload `server/navigator-v5.php` til **samme mappe som `api.php`** på `minside.hotservice.dk`. Den bruger din eksisterende `data/settings.php` uden ændringer. Besøg `https://minside.hotservice.dk/navigator-v5.php?action=version` og kontroller `"apiVersion":5` og `"routeSource":"mysql"`.
2. Log ind som administrator i Busdrift **i samme browser**. Åbn derefter `https://minside.hotservice.dk/navigator-v5.php?action=worker-setup`. Tryk **Opret eller udskift arbejder-nøgle**. Kopiér den viste nøgle. Det er en særskilt nøgle til rutearbejderen; den er ikke din MySQL-adgang eller chaufførkode. Hvis du opretter en ny nøgle, stopper den gamle med at virke.
3. På Windows-pc'en: hent mappen `navigator-worker` fra projektet, fx til `C:\busdrift\navigator-worker`. Kopiér `worker.env.example` til `worker.env`, og erstat værdien af `NAVIGATOR_TOKEN` med nøglen. Behold `worker.env` på Windows-pc'en og del den ikke.
4. Åbn PowerShell i denne mappe, og kør `docker compose --env-file worker.env up -d`. Se logs med `docker compose --env-file worker.env logs -f navigator-ruter`. Hvis OSRM og Nominatim kører i Docker Desktop på samme pc og portene er publiceret på Windows, kan rutearbejderen nå dem på `host.docker.internal`. Ret `NOMINATIM_URL` og `OSRM_URL` i `worker.env` hvis dine lokale adresser er anderledes.
5. Installer Navigator v5 APK, log ind med chaufførnummer og PIN, og vælg dagens tur. Første rute kan stå som **Ruten beregnes …** nogle sekunder. Windows-rutearbejderen gemmer den derefter i MySQL, og kortet opdateres automatisk.

`worker.env` er udelukket fra GitHub. Den eksisterende `settings.php` og alle MySQL-oplysninger forbliver kun på PHP-serveren. PC'en skal være tændt, og Docker Desktop skal køre, når nye ruter skal beregnes. Der bruges ingen indgående portåbning i modemmet.

## Hvis der ikke kommer en rute

Se først `docker compose --env-file worker.env logs --tail=30 navigator-ruter`. `HTTP 403` betyder forkert arbejder-nøgle; hent en ny på `worker-setup` og opdatér `worker.env`. En fejl fra `host.docker.internal:8080` eller `:5000` betyder, at den lokale Nominatim eller OSRM ikke svarer fra rutearbejder-containeren. Hvis serveren ikke svarer på HTTPS, kontroller `NAVIGATOR_SERVER` og at pc'en har internetadgang. Fejlen bliver også vist på chaufførens kort efter rutearbejderen har forsøgt at beregne den.
