"""Busdrift route worker. Polls PHP over HTTPS; never connects to MySQL."""
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

SITE = os.environ.get("NAVIGATOR_SERVER", "https://minside.hotservice.dk").rstrip("/")
TOKEN = os.environ.get("NAVIGATOR_TOKEN", "")
GEOCODER = os.environ.get("NOMINATIM_URL", "http://host.docker.internal:8080").rstrip("/")
ROUTER = os.environ.get("OSRM_URL", "http://host.docker.internal:5000").rstrip("/")
if not SITE.startswith("https://") or not re.fullmatch(r"[a-f0-9]{64}", TOKEN):
    raise SystemExit("Angiv NAVIGATOR_SERVER med HTTPS og NAVIGATOR_TOKEN fra administratorens opsætning.")


def fetch(url, data=None, worker=False):
    headers = {"Accept": "application/json", "User-Agent": "Busdrift-Windows-Rutearbejder/5.0"}
    if worker:
        headers["X-Navigator-Worker-Token"] = TOKEN
    if data is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
        data = json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=22) as response:
            body = response.read(5_000_000)
            value = json.loads(body)
            if not isinstance(value, (dict, list)):
                raise ValueError("Ugyldigt JSON-svar")
            if isinstance(value, dict) and "error" in value:
                raise ValueError(value["error"])
            return value
    except urllib.error.HTTPError as exc:
        raise RuntimeError("HTTP " + str(exc.code)) from exc


def calculate(job):
    address = job["address"]
    query = urllib.parse.urlencode({"q": address, "format": "jsonv2", "addressdetails": 1, "limit": 5})
    candidates = fetch(GEOCODER + "/search?" + query)
    match = re.search(r"\b(\d{4})\b", address)
    postcode = match.group(1) if match else None
    target = next((c for c in candidates if "lat" in c and "lon" in c and
                   (postcode is None or c.get("address", {}).get("postcode") == postcode)), None)
    if target is None:
        raise ValueError("Adresseopslag fandt ikke: " + address)
    lat, lon = float(job["latitude"]), float(job["longitude"])
    coords = f"{lon},{lat};{target['lon']},{target['lat']}"
    url = ROUTER + "/route/v1/driving/" + coords + "?" + urllib.parse.urlencode(
        {"overview": "full", "steps": "true", "geometries": "geojson"})
    routes = fetch(url)
    if not routes.get("routes"):
        raise ValueError("OSRM fandt ingen rute")
    chosen = routes["routes"][0]
    steps = []
    for step in chosen["legs"][0]["steps"]:
        move = step.get("maneuver", {})
        where = move.get("location", [])
        if len(where) < 2:
            continue
        steps.append({"type": move.get("type", ""), "modifier": move.get("modifier", ""),
                      "road": step.get("name", ""), "distance": step.get("distance", 0),
                      "lon": where[0], "lat": where[1]})
    return {"duration": round(chosen["duration"]), "distance": round(chosen["distance"]),
            "geometry": chosen["geometry"]["coordinates"], "steps": steps}


def run():
    print("Rutearbejder kører: Windows beregner; PHP/MySQL gemmer.", flush=True)
    while True:
        try:
            items = fetch(SITE + "/navigator-v5.php?action=worker-jobs", worker=True)["jobs"]
            for job in items:
                metadata = {"driverId": job["driver_id"], "date": job["work_date"],
                            "key": job["waypoint_key"], "latitude": job["latitude"],
                            "longitude": job["longitude"]}
                try:
                    result = {**metadata, "route": calculate(job)}
                    fetch(SITE + "/navigator-v5.php?action=worker-result", result, worker=True)
                    print("Rute gemt: " + str(job["waypoint_key"]), flush=True)
                except Exception as exc:
                    print("Rute kunne ikke beregnes: " + str(exc), flush=True)
                    try:
                        fetch(SITE + "/navigator-v5.php?action=worker-result",
                              {**metadata, "failure": "Windows-ruteserver: " + str(exc)[:190]}, worker=True)
                    except Exception as report_error:
                        print("Kunne ikke sende fejl: " + str(report_error), flush=True)
            time.sleep(4)
        except KeyboardInterrupt:
            break
        except Exception as exc:
            print("Rutearbejder: " + str(exc), flush=True)
            time.sleep(15)


if __name__ == "__main__":
    run()
