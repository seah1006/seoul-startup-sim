param([string]$BaseUrl='http://localhost:8081',[string]$AreaCode='3110829',[string]$Quarter='20262')
$ErrorActionPreference='Stop'
Invoke-RestMethod "$BaseUrl/api/industries?playable=true" | ConvertTo-Json
Invoke-RestMethod "$BaseUrl/api/areas/$AreaCode/report?quarter=$Quarter" | ConvertTo-Json -Depth 8
Invoke-RestMethod "$BaseUrl/api/areas/$AreaCode/stores?serviceCode=CS100010&lng=126.905&lat=37.556&radius=300" | ConvertTo-Json -Depth 8
# 메인 DB에서 실행할 읽기 전용 검증 SQL. .env 및 자격 증명은 출력하지 않는다.
@"
SELECT service_code,count(*) FROM industry_mappings WHERE service_code LIKE 'CS1%' GROUP BY service_code ORDER BY service_code;
SELECT count(*) AS unmapped_stores FROM stores WHERE service_code IS NULL;
SELECT conname,convalidated FROM pg_constraint WHERE conname LIKE 'area_%_fk';
SELECT 'sales-area' AS kind,count(*) FROM area_sales_q x LEFT JOIN trade_areas a USING(area_code) WHERE a.area_code IS NULL
UNION ALL SELECT 'sales-service',count(*) FROM area_sales_q x LEFT JOIN service_industries i USING(service_code) WHERE i.service_code IS NULL
UNION ALL SELECT 'stores-area',count(*) FROM area_stores_q x LEFT JOIN trade_areas a USING(area_code) WHERE a.area_code IS NULL
UNION ALL SELECT 'stores-service',count(*) FROM area_stores_q x LEFT JOIN service_industries i USING(service_code) WHERE i.service_code IS NULL
UNION ALL SELECT 'floating-area',count(*) FROM area_floating_pop_q x LEFT JOIN trade_areas a USING(area_code) WHERE a.area_code IS NULL;
SELECT quarter,count(*) FROM area_worker_pop_q GROUP BY quarter ORDER BY quarter;
"@
