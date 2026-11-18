# PowerShell 7: CONTENT_DRAFT.md JSON 블록을 V8 콘텐츠 INSERT로 변환한다.
# 실행: pwsh -File docs/tasks/drafts-004b/generate-events.ps1 [-Check]
[CmdletBinding()]
param([switch]$Check)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$source = Get-Content (Join-Path $PSScriptRoot 'CONTENT_DRAFT.md') -Raw
$schema = Get-Content (Join-Path $PSScriptRoot 'V8__events.sql') -Raw
$target = Join-Path $root 'backend/src/main/resources/db/migration/V8__events.sql'
$blocks = [regex]::Matches($source, '(?ms)^```json\s*\r?\n(.*?)^```')
$points = @()
$reactions = @()
$moments = @()
foreach ($block in $blocks) {
    foreach ($item in @((ConvertFrom-Json -InputObject $block.Groups[1].Value -AsHashtable))) {
        if ($item.ContainsKey('options')) { $points += $item }
        elseif ($item.ContainsKey('kind')) { $reactions += $item }
        elseif ($item.ContainsKey('difficulty')) { $moments += $item }
        else { throw '알 수 없는 콘텐츠 JSON 블록입니다.' }
    }
}
if ($points.Count -ne 12 -or $reactions.Count -ne 36 -or $moments.Count -ne 12) {
    throw "콘텐츠 수 불일치: $($points.Count)/$($reactions.Count)/$($moments.Count)"
}
function SqlValue($value) {
    if ($null -eq $value) { return 'NULL' }
    if ($value -is [bool]) { return $value.ToString().ToLowerInvariant() }
    if ($value -is [string]) { return "'" + $value.Replace("'", "''") + "'" }
    if ($value -is [ValueType]) { return $value.ToString([Globalization.CultureInfo]::InvariantCulture) }
    throw 'SQL에 직접 쓸 수 없는 값입니다.'
}
function JsonValue($value) {
    return (SqlValue (ConvertTo-Json -InputObject $value -Depth 50 -Compress)) + '::jsonb'
}
function GetDefault($item, $key, $fallback) {
    if ($item.ContainsKey($key)) { return $item[$key] }
    return ,$fallback
}
$lines = [Collections.Generic.List[string]]::new()
$lines.Add('-- 아래 INSERT는 generate-events.ps1로 생성한다. 직접 수정하지 않는다.')
foreach ($point in $points) {
    if ($point.options.Count -lt 2 -or $point.options.Count -gt 3) { throw "선택지 수 오류: $($point.code)" }
    $values = @(
        (SqlValue $point.code), (SqlValue $point.title), (SqlValue $point.description),
        (JsonValue $point.conditionExpr), (SqlValue $point.baseProbability),
        (JsonValue (GetDefault $point 'statBonus' @())),
        (SqlValue (GetDefault $point 'probMin' 0)), (SqlValue (GetDefault $point 'probMax' 1)),
        (SqlValue $point.cooldownQuarters), (SqlValue $point.maxFires),
        (SqlValue (GetDefault $point 'minTurn' 0)), (SqlValue $point.priority), (SqlValue $point.enabled)
    )
    $lines.Add('INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES (' + ($values -join ', ') + ');')
    foreach ($option in $point.options) {
        $values = @(
            (SqlValue $option.orderNo), (SqlValue $option.label), (JsonValue $option.successFormula),
            (JsonValue $option.onSuccess), (JsonValue $option.onFail),
            (SqlValue $option.successText), (SqlValue $option.failText)
        )
        $lines.Add('INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, ' + ($values -join ', ') + ' FROM turning_points WHERE code = ' + (SqlValue $point.code) + ';')
    }
}
foreach ($reaction in $reactions) {
    $values = @(
        (SqlValue $reaction.code), (SqlValue $reaction.kind), (SqlValue $reaction.author),
        (SqlValue $reaction.rating), (SqlValue $reaction.weight), (JsonValue $reaction.conditionExpr),
        (SqlValue $reaction.text), (SqlValue $reaction.enabled)
    )
    $lines.Add('INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES (' + ($values -join ', ') + ');')
}
foreach ($moment in $moments) {
    $values = @(
        (SqlValue $moment.code), (SqlValue $moment.title), (SqlValue $moment.stat),
        (SqlValue $moment.difficulty), (SqlValue $moment.weight), (JsonValue $moment.conditionExpr),
        (SqlValue $moment.successText), (SqlValue $moment.failText),
        (JsonValue $moment.onSuccess), (JsonValue $moment.onFail), (SqlValue $moment.enabled)
    )
    $lines.Add('INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES (' + ($values -join ', ') + ');')
}
# 스키마 원본은 그대로 보존하고 콘텐츠만 끝에 추가한다. 개행은 운영체제와 무관하게 LF다.
$output = ($schema.TrimEnd() + "`n`n" + ($lines -join "`n") + "`n").Replace("`r`n", "`n")
if ($Check) {
    if (!(Test-Path $target) -or [IO.File]::ReadAllText($target) -cne $output) {
        throw 'V8이 원본과 다릅니다. 생성 스크립트를 다시 실행하세요.'
    }
} else {
    [IO.File]::WriteAllText($target, $output, [Text.UTF8Encoding]::new($false))
}
$optionCount = ($points | ForEach-Object { $_.options.Count } | Measure-Object -Sum).Sum
Write-Output "V8 확인: 전환점 $($points.Count), 선택지 $optionCount, 반응 $($reactions.Count), 순간 $($moments.Count)"
