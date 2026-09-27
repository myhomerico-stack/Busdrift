<?php
declare(strict_types=1);
// Busdrift Navigator, PHP 8.0. Gemmes ved siden af api.php; data/settings.php læses kun her.
ini_set('display_errors', '0');
date_default_timezone_set('Europe/Copenhagen');
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
// Offentlig versionskontrol uden adgang til MySQL eller chaufførdata.
if (($_GET['action'] ?? '') === 'version' && ($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'GET') {
    echo json_encode(['navigator' => true, 'apiVersion' => 4, 'diagnostics' => '4.0', 'routeAction' => 'navigate']);
    exit;
}

function answer(int $status, array $value): void {
    // Synology/Web Station kan erstatte HTTP-fejlsider med HTML. Hold svaret som JSON.
    if ($status >= 400) { $value['status'] = $status; http_response_code(200); }
    else http_response_code($status);
    echo json_encode($value, JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);
    exit;
}
function requestBody(): array {
    $raw = file_get_contents('php://input');
    if ($raw === false || strlen($raw) > 16384) answer(413, ['error' => 'For meget data.']);
    $v = json_decode($raw, true);
    return is_array($v) ? $v : [];
}
function q(PDO $db, string $sql, array $args = []): PDOStatement {
    $stmt = $db->prepare($sql); $stmt->execute($args); return $stmt;
}
function initialize(PDO $db): void {
    $db->exec('CREATE TABLE IF NOT EXISTS navigator_devices (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, driver_id BIGINT UNSIGNED NOT NULL, token_hash CHAR(64) NOT NULL UNIQUE, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, revoked_at DATETIME NULL, KEY (driver_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
    $db->exec('CREATE TABLE IF NOT EXISTS navigator_login_limits (fingerprint CHAR(64) PRIMARY KEY, attempts INT UNSIGNED NOT NULL DEFAULT 0, window_start DATETIME NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
    $db->exec('CREATE TABLE IF NOT EXISTS navigator_days (driver_id BIGINT UNSIGNED NOT NULL, work_date DATE NOT NULL, completed_at DATETIME NULL, PRIMARY KEY(driver_id,work_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
    $db->exec('CREATE TABLE IF NOT EXISTS navigator_waypoints (driver_id BIGINT UNSIGNED NOT NULL, work_date DATE NOT NULL, waypoint_key VARCHAR(100) NOT NULL, completed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(driver_id,work_date,waypoint_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
    $db->exec('CREATE TABLE IF NOT EXISTS navigator_positions (bus_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, driver_id BIGINT UNSIGNED NOT NULL, tour_id BIGINT UNSIGNED NOT NULL, latitude DECIMAL(10,7) NOT NULL, longitude DECIMAL(10,7) NOT NULL, accuracy_m DECIMAL(8,2) NULL, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
}
function authorized(PDO $db): array {
    $header = (string)($_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '');
    if ($header === '' && function_exists('getallheaders')) {
        foreach (getallheaders() as $key => $value) if (strcasecmp($key, 'Authorization') === 0) $header = (string)$value;
    }
    if (!preg_match('/^Bearer ([a-f0-9]{64})$/D', $header, $m)) answer(401, ['error' => 'Log ind igen.']);
    $row = q($db, 'SELECT d.driver_id,r.label,r.data FROM navigator_devices d JOIN records r ON r.id=d.driver_id AND r.kind=? WHERE d.token_hash=? AND d.revoked_at IS NULL LIMIT 1', ['chauffor', hash('sha256', $m[1])])->fetch(PDO::FETCH_ASSOC);
    if (!$row) answer(401, ['error' => 'Log ind igen.']);
    $details = json_decode((string)$row['data'], true);
    return ['id' => (int)$row['driver_id'], 'number' => (string)($details['number'] ?? ''), 'name' => (string)$row['label'], 'tokenHash' => hash('sha256', $m[1])];
}
function workDate(?string $requested): string {
    $date = $requested === null || $requested === '' ? date('Y-m-d') : $requested;
    $valid = DateTimeImmutable::createFromFormat('!Y-m-d', $date);
    if (!$valid || $valid->format('Y-m-d') !== $date || $date < date('Y-m-d', strtotime('-7 days')) || $date > date('Y-m-d', strtotime('+30 days'))) answer(400, ['error' => 'Vælg en dato fra de seneste 7 til de næste 30 dage.']);
    return $date;
}
function recordAddress(?string $json): string {
    $r = json_decode((string)$json, true);
    if (!is_array($r)) return '';
    return trim(implode(', ', array_filter([trim((string)($r['local'] ?? '')), trim((string)($r['post'] ?? '') . ' ' . (string)($r['city'] ?? ''))], function($v){ return $v !== ''; })));
}
function route(PDO $db, int $driverId, string $date): array {
    $tours = q($db, "SELECT t.id,t.garage_id garageId,t.bus_id busId,TIME_FORMAT(t.start_time,'%H:%i') startTime,TIME_FORMAT(t.end_time,'%H:%i') endTime,t.stops,t.description,c.label customerName,g.label garageName,g.data garageData,b.label busName,b.data busData FROM tours t LEFT JOIN records c ON c.id=t.customer_id AND c.kind='kunde' LEFT JOIN records g ON g.id=t.garage_id AND g.kind='garage' LEFT JOIN records b ON b.id=t.bus_id AND b.kind='bus' WHERE t.driver_id=? AND t.date=? AND t.status NOT IN ('Aflyst','Annulleret') ORDER BY t.start_time,t.id", [$driverId,$date])->fetchAll(PDO::FETCH_ASSOC);
    $waypoints=[]; $summaries=[]; $garageId=null; $busId=null; $garageName=''; $garageAddress=''; $lastTour=0; $section=0;
    foreach ($tours as $t) {
        $id=(int)$t['id']; $currentGarage=(int)$t['garageId']; $address=recordAddress($t['garageData']);
        if ($currentGarage<1 || $address==='') answer(409, ['error'=>'Tur '.$id.' mangler en garage med adresse. Ret turen i Busdrift.']);
        $currentBus=(int)$t['busId'];
        if ($currentBus<1) answer(409, ['error'=>'Tur '.$id.' mangler en bus. Ret turen i Busdrift.']);
        $stops=json_decode((string)$t['stops'], true);
        if (!is_array($stops) || count($stops)<2 || count($stops)>30) answer(409, ['error'=>'Tur '.$id.' mangler en gyldig stopliste.']);
        if ($garageId !== $currentGarage || $busId !== $currentBus) {
            if ($garageId !== null) $waypoints[]=['key'=>'garage:'.$section.':return','type'=>'garage_return','title'=>'Retur til '.$garageName,'address'=>$garageAddress,'tourId'=>$lastTour,'pause'=>0];
            $section++;
            $garageId=$currentGarage; $busId=$currentBus; $garageName=(string)($t['garageName'] ?: 'Garage'); $garageAddress=$address;
            $waypoints[]=['key'=>'garage:'.$section.':start','type'=>'garage_start','title'=>'Start ved '.$garageName,'address'=>$garageAddress,'tourId'=>$id,'pause'=>0];
        }
        $busData=json_decode((string)$t['busData'], true);
        $busName=trim((string)($busData['number'] ?? '').' · '.(string)($t['busName'] ?? ''),' ·');
        $summaries[]=['id'=>$id,'customer'=>(string)($t['customerName'] ?: 'Kunde'),'bus'=>$busName,'startTime'=>$t['startTime'],'endTime'=>$t['endTime'],'description'=>(string)$t['description']];
        foreach (array_values($stops) as $i=>$stop) {
            $stopAddress=trim((string)($stop['address'] ?? ''));
            if ($stopAddress==='') answer(409, ['error'=>'Adresse mangler ved stop '.($i+1).' på tur '.$id.'.']);
            $waypoints[]=['key'=>'tour:'.$id.':stop:'.$i,'type'=>'customer','title'=>(string)($t['customerName'] ?: 'Kunde').' · stop '.($i+1),'address'=>$stopAddress,'tourId'=>$id,'pause'=>max(0,min(1440,(int)($stop['pause'] ?? 0)))];
        }
        $lastTour=$id;
    }
    if ($garageId !== null) $waypoints[]=['key'=>'garage:'.$section.':return','type'=>'garage_return','title'=>'Retur til '.$garageName,'address'=>$garageAddress,'tourId'=>$lastTour,'pause'=>0];
    $completed=q($db,'SELECT waypoint_key FROM navigator_waypoints WHERE driver_id=? AND work_date=?',[$driverId,$date])->fetchAll(PDO::FETCH_COLUMN);
    $completedMap=array_fill_keys($completed,true);
    foreach ($waypoints as &$item) $item['done']=isset($completedMap[$item['key']]); unset($item);
    $done=(bool)q($db,'SELECT completed_at FROM navigator_days WHERE driver_id=? AND work_date=?',[$driverId,$date])->fetchColumn();
    return ['date'=>$date,'tours'=>$summaries,'waypoints'=>$waypoints,'completed'=>$done];
}
function navigationJson(string $url, string $service): array {
    if (!function_exists('curl_init')) answer(503,['error'=>'PHP cURL mangler til '.$service.'.']);
    $ch=curl_init($url);
    curl_setopt_array($ch,[CURLOPT_RETURNTRANSFER=>true,CURLOPT_CONNECTTIMEOUT=>5,CURLOPT_TIMEOUT=>15,CURLOPT_FOLLOWLOCATION=>false,CURLOPT_MAXREDIRS=>0,CURLOPT_HTTPHEADER=>['Accept: application/json','User-Agent: Busdrift-Navigator/2.0']]);
    $body=curl_exec($ch);$status=(int)curl_getinfo($ch,CURLINFO_RESPONSE_CODE);$errno=curl_errno($ch);curl_close($ch);
    if ($body===false) answer(502,['error'=>$service.' kunne ikke kontaktes (cURL '.$errno.'). Tjek forbindelsen fra PHP-serveren til ruteserveren.']);
    if ($status!==200) answer(502,['error'=>$service.' svarede HTTP '.$status.'. Kontrollér adressen under Database.']);
    if (strlen($body)>4000000) answer(502,['error'=>$service.' sendte for mange data.']);
    $json=json_decode($body,true);
    if (!is_array($json)) answer(502,['error'=>$service.' sendte ikke gyldige JSON-data.']);
    return $json;
}
function navUrl(string $base,string $path,array $params=[]): string {
    $p=parse_url($base);
    if (!is_array($p) || !in_array($p['scheme']??'',['http','https'],true) || empty($p['host']) || isset($p['user']) || isset($p['pass']) || isset($p['query']) || isset($p['fragment'])) answer(503,['error'=>'Ruteopsætningen under Database er ugyldig.']);
    return rtrim($base,'/').'/'.ltrim($path,'/').($params?'?'.http_build_query($params,'','&',PHP_QUERY_RFC3986):'');
}
function navigation(PDO $db,int $driverId,array $settings,array $input): void {
    $local=$settings['localRouting']??null;
    if (!is_array($local) || empty($local['osrmUrl']) || empty($local['geocoderUrl'])) answer(409,['error'=>'Indstil den lokale OSRM og adresseserver under Database for at bruge kortnavigation.']);
    $lat=filter_var($input['latitude']??null,FILTER_VALIDATE_FLOAT);$lon=filter_var($input['longitude']??null,FILTER_VALIDATE_FLOAT);
    $key=(string)($input['key']??'');
    if ($lat===false || $lon===false || $lat===null || $lon===null || abs($lat)>90 || abs($lon)>180 || strlen($key)>100) answer(400,['error'=>'GPS-position eller stop er ugyldigt.']);
    $today=date('Y-m-d');$day=route($db,$driverId,$today);$next=null;
    foreach($day['waypoints'] as $point) if (!$point['done']) { $next=$point;break; }
    if (!$next || $next['key']!==$key || $next['type']==='garage_start' || $day['completed']) answer(409,['error'=>'Dette er ikke næste stop på dagens rute.']);
    $address=$next['address'];
    $geocode=navigationJson(navUrl((string)$local['geocoderUrl'],'search',['q'=>$address,'format'=>'jsonv2','addressdetails'=>1,'limit'=>5]),'Adresseopslag');
    $postcode=preg_match('/\b(\d{4})\b/',$address,$m)?$m[1]:null;
    $target=null;
    foreach($geocode as $candidate) if(is_array($candidate) && isset($candidate['lat'],$candidate['lon']) && (!$postcode || ($candidate['address']['postcode']??'')===$postcode)) { $target=$candidate;break; }
    if (!$target) answer(422,['error'=>'Stoppets adresse kunne ikke findes: '.$address]);
    $coords=$lon.','.$lat.';'.$target['lon'].','.$target['lat'];
    $osrm=navigationJson(navUrl((string)$local['osrmUrl'],'route/v1/driving/'.$coords,['overview'=>'full','steps'=>'true','geometries'=>'geojson']),'OSRM');
    $path=$osrm['routes'][0]??null;
    if (!is_array($path) || !isset($path['duration'],$path['distance'],$path['geometry']['coordinates'],$path['legs'][0]['steps'])) answer(502,['error'=>'OSRM fandt ingen rute til næste stop.']);
    $steps=[];
    foreach($path['legs'][0]['steps'] as $step) {
        $maneuver=$step['maneuver']??[];$location=$maneuver['location']??[];
        if (!isset($location[0],$location[1])) continue;
        $steps[]=['type'=>(string)($maneuver['type']??''),'modifier'=>(string)($maneuver['modifier']??''),'road'=>(string)($step['name']??''),'distance'=>(float)($step['distance']??0),'lon'=>(float)$location[0],'lat'=>(float)$location[1]];
    }
    answer(200,['key'=>$key,'address'=>$address,'duration'=>(int)ceil($path['duration']),'distance'=>(int)ceil($path['distance']),'geometry'=>$path['geometry']['coordinates'],'steps'=>$steps]);
}
function login(PDO $db, array $input): void {
    $number=trim((string)($input['number']??'')); $pin=(string)($input['pin']??'');
    if ($number==='' || strlen($number)>50 || strlen($pin)>200) answer(400,['error'=>'Indtast chaufførnummer og PIN.']);
    $key=hash('sha256',(string)($_SERVER['REMOTE_ADDR']??'')."\n".$number);
    $db->beginTransaction();
    q($db,'INSERT IGNORE INTO navigator_login_limits(fingerprint,attempts,window_start) VALUES(?,0,UTC_TIMESTAMP())',[$key]);
    $limit=q($db,'SELECT attempts,window_start FROM navigator_login_limits WHERE fingerprint=? FOR UPDATE',[$key])->fetch(PDO::FETCH_ASSOC);
    $age=time()-strtotime($limit['window_start'].' UTC');
    if ($age>900) { q($db,'UPDATE navigator_login_limits SET attempts=0,window_start=UTC_TIMESTAMP() WHERE fingerprint=?',[$key]);$limit['attempts']=0; }
    if ((int)$limit['attempts']>=5) { $db->commit(); answer(429,['error'=>'For mange forsøg. Prøv igen om 15 minutter.']); }
    $row=q($db,"SELECT data FROM records WHERE kind='driver_access' AND JSON_UNQUOTE(JSON_EXTRACT(data,'$.number'))=? LIMIT 1",[$number])->fetch(PDO::FETCH_ASSOC);
    $entry=$row?json_decode((string)$row['data'],true):[];
    $valid=$entry && password_verify($pin,(string)($entry['hash']??''));
    $driverId=(int)($entry['driverId']??0);
    $driver=$valid&&$driverId>0?q($db,'SELECT id FROM records WHERE id=? AND kind=?',[$driverId,'chauffor'])->fetchColumn():false;
    if (!$valid || !$driver) {
        q($db,'UPDATE navigator_login_limits SET attempts=attempts+1 WHERE fingerprint=?',[$key]);
        $db->commit();answer(401,['error'=>'Forkert chaufførnummer eller PIN.']);
    }
    q($db,'DELETE FROM navigator_login_limits WHERE fingerprint=?',[$key]);
    $token=bin2hex(random_bytes(32));
    q($db,'INSERT INTO navigator_devices(driver_id,token_hash) VALUES(?,?)',[$driverId,hash('sha256',$token)]);
    $db->commit();answer(200,['token'=>$token]);
}

try {
    $config=__DIR__.'/data/settings.php';
    if (!is_file($config)) answer(503,['error'=>'Busdrift er ikke installeret på denne server.']);
    $settings=require $config; $m=$settings['mysql']??null;
    if (!is_array($m)) answer(503,['error'=>'MySQL-opsætning mangler.']);
    $options=[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION,PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC,PDO::ATTR_EMULATE_PREPARES=>false];
    if (!empty($m['ssl'])) $options[PDO::MYSQL_ATTR_SSL_VERIFY_SERVER_CERT]=true;
    $db=new PDO('mysql:host='.$m['host'].';port='.$m['port'].';dbname='.$m['database'].';charset=utf8mb4',$m['user'],$m['password'],$options);
    initialize($db);
    $action=(string)($_GET['action']??'');$method=$_SERVER['REQUEST_METHOD']??'GET';
    if ($action==='login' && $method==='POST') login($db,requestBody());
    $driver=authorized($db);$id=$driver['id'];
    if ($action==='day' && $method==='GET') { $result=route($db,$id,workDate(isset($_GET['date'])?(string)$_GET['date']:null));$result['driver']=['number'=>$driver['number'],'name'=>$driver['name']];answer(200,$result); }
    if ($action==='navigate' && $method==='POST') navigation($db,$id,$settings,requestBody());
    if ($action==='waypoint' && $method==='POST') {
        $input=requestBody();$date=workDate((string)($input['date']??''));$key=(string)($input['key']??'');
        if ($date!==date('Y-m-d')) answer(400,['error'=>'Kun dagens kørsler kan udføres.']);
        if (strlen($key)<3 || strlen($key)>100) answer(400,['error'=>'Ugyldigt stop.']);
        $db->beginTransaction();
        q($db,'INSERT INTO navigator_days(driver_id,work_date) VALUES(?,?) ON DUPLICATE KEY UPDATE driver_id=driver_id',[$id,$date]);
        $done=q($db,'SELECT completed_at FROM navigator_days WHERE driver_id=? AND work_date=? FOR UPDATE',[$id,$date])->fetchColumn();
        if ($done) { $db->rollBack();answer(409,['error'=>'Dagen er allerede afsluttet.']); }
        $current=route($db,$id,$date);$next=null;
        foreach ($current['waypoints'] as $item) if (!$item['done']) {$next=$item;break;}
        if (!$next || $next['key']!==$key) { $db->rollBack();answer(409,['error'=>'Vælg det næste stop i rækkefølgen.']); }
        q($db,'INSERT INTO navigator_waypoints(driver_id,work_date,waypoint_key) VALUES(?,?,?)',[$id,$date,$key]);
        $db->commit();answer(200,['ok'=>true]);
    }
    if ($action==='finish' && $method==='POST') {
        $date=workDate((string)(requestBody()['date']??''));
        if ($date!==date('Y-m-d')) answer(400,['error'=>'Kun dagens kørsler kan afsluttes.']);
        $db->beginTransaction();
        q($db,'INSERT INTO navigator_days(driver_id,work_date) VALUES(?,?) ON DUPLICATE KEY UPDATE driver_id=driver_id',[$id,$date]);
        $already=q($db,'SELECT completed_at FROM navigator_days WHERE driver_id=? AND work_date=? FOR UPDATE',[$id,$date])->fetchColumn();
        if ($already) { $db->rollBack();answer(409,['error'=>'Dagen er allerede afsluttet.']); }
        $current=route($db,$id,$date);
        if (!$current['waypoints']) { $db->rollBack();answer(409,['error'=>'Der er ingen ture denne dag.']); }
        foreach ($current['waypoints'] as $item) if (!$item['done']) { $db->rollBack();answer(409,['error'=>'Alle stop og returen til garagen skal være udført.']); }
        q($db,'UPDATE navigator_days SET completed_at=UTC_TIMESTAMP() WHERE driver_id=? AND work_date=?',[$id,$date]);
        q($db,'UPDATE navigator_devices SET revoked_at=UTC_TIMESTAMP() WHERE token_hash=?',[$driver['tokenHash']]);
        $db->commit();answer(200,['finished'=>true,'loggedOut'=>true]);
    }
    if ($action==='position' && $method==='POST') {
        $input=requestBody();$tourId=filter_var($input['tourId']??null,FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        $lat=filter_var($input['latitude']??null,FILTER_VALIDATE_FLOAT);$lon=filter_var($input['longitude']??null,FILTER_VALIDATE_FLOAT);$accuracy=filter_var($input['accuracy']??null,FILTER_VALIDATE_FLOAT);
        if (!$tourId || $lat===false || $lon===false || $lat===null || $lon===null || abs($lat)>90 || abs($lon)>180 || $accuracy===false || $accuracy===null || $accuracy<0 || $accuracy>10000) answer(400,['error'=>'Ugyldig GPS-position.']);
        $bus=q($db,'SELECT bus_id FROM tours WHERE id=? AND driver_id=? AND date BETWEEN DATE_SUB(CURDATE(),INTERVAL 1 DAY) AND DATE_ADD(CURDATE(),INTERVAL 1 DAY)',[$tourId,$id])->fetchColumn();
        if (!$bus) answer(404,['error'=>'Turen mangler bus.']);
        q($db,'INSERT INTO navigator_positions(bus_id,driver_id,tour_id,latitude,longitude,accuracy_m) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE driver_id=VALUES(driver_id),tour_id=VALUES(tour_id),latitude=VALUES(latitude),longitude=VALUES(longitude),accuracy_m=VALUES(accuracy_m),updated_at=CURRENT_TIMESTAMP',[(int)$bus,$id,$tourId,$lat,$lon,$accuracy]);
        answer(200,['ok'=>true]);
    }
    if ($action==='logout' && $method==='POST') {q($db,'UPDATE navigator_devices SET revoked_at=UTC_TIMESTAMP() WHERE token_hash=?',[$driver['tokenHash']]);answer(200,['loggedOut'=>true]);}
    answer(404,['error'=>'Handlingen findes ikke.']);
} catch (Throwable $e) {
    if (isset($db) && $db->inTransaction()) $db->rollBack();
    error_log('Busdrift Navigator: '.$e->getMessage());
    answer(503,['error'=>'Serveren kunne ikke fuldføre handlingen. Kontroller PHP-log og MySQL.']);
}
