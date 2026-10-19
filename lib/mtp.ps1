param(
  [Parameter(Mandatory=$true)][string]$Action,
  [string]$Device = "",
  [string]$Segments = "[]",
  [string]$Remote = "",
  [string]$Local = "",
  [int]$TimeoutSec = 45
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Out-Json($obj) {
  [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress -Depth 8))
}

function Get-ChildItemByName($folder, $name) {
  foreach ($i in $folder.Items()) { if ($i.Name -eq $name) { return $i } }
  return $null
}

# 系统语言不同，删除动词是本地化的（中文系统是「删除(&D)」），按关键词挑
function Invoke-DeleteVerb($item) {
  $names = @()
  foreach ($v in $item.Verbs()) { $names += $v.Name }
  $hit = $names | Where-Object { $_ -match '删除' -or $_ -match 'Delete' } | Select-Object -First 1
  if (-not $hit) { throw "找不到删除命令，可用命令：$($names -join ' / ')" }
  $item.InvokeVerb($hit)
}

# 第一段可以写 __storage__，自动定位真正存放 Android 目录的那一层（不同品牌层级不一样）
function Resolve-Storage($deviceItem) {
  $root = $deviceItem.GetFolder
  if (-not $root) { throw "设备没有可浏览的存储（请在手机上选「传输文件」并解锁）" }
  if (Get-ChildItemByName $root "Android") { return @{ folder = $root; name = $deviceItem.Name } }
  foreach ($i in $root.Items()) {
    $f = $null
    try { $f = $i.GetFolder } catch { }
    if ($f -and (Get-ChildItemByName $f "Android")) { return @{ folder = $f; name = $i.Name } }
  }
  return @{ folder = $root; name = $deviceItem.Name }
}

function Navigate-To($deviceItem, $segs) {
  $cur = $null
  $built = ""
  foreach ($s in $segs) {
    if ($s -eq "__storage__") {
      $st = Resolve-Storage $deviceItem
      $cur = $st.folder
      $built = "\" + $st.name
      continue
    }
    if (-not $cur) { throw "路径必须以 __storage__ 开头" }
    $item = Get-ChildItemByName $cur $s
    if (-not $item) { throw "路径不存在：$built\$s" }
    $next = $item.GetFolder
    if (-not $next) { throw "$s 不是文件夹" }
    $cur = $next
    $built = "$built\$s"
  }
  if (-not $cur) { $st = Resolve-Storage $deviceItem; $cur = $st.folder; $built = "\" + $st.name }
  return @{ folder = $cur; path = $built }
}

try {
  $shell = New-Object -ComObject Shell.Application
  $pc = $shell.Namespace(17)
  if (-not $pc) { throw "无法枚举便携设备" }

  $dev = $null
  foreach ($it in $pc.Items()) {
    if ($Device -and $it.Name -like "*$Device*") { $dev = $it; break }
  }
  if (-not $dev) { throw "找不到设备：'$Device'（确认已连接、解锁、并选了「传输文件」）" }

  $segs = @()
  if ($Segments -and $Segments -ne "[]") {
    $parsed = $Segments | ConvertFrom-Json
    if ($parsed -is [System.Array]) { foreach ($x in $parsed) { $segs += [string]$x } }
    else { $segs += [string]$parsed }
  }

  $nav = Navigate-To $dev $segs
  $cur = $nav.folder

  switch ($Action) {
    "probe" {
      Out-Json @{ ok = $true; path = $nav.path; items = @($cur.Items()).Count; raw = $Segments; segCount = @($segs).Count; segs = @($segs) }
    }
    "list" {
      $items = @()
      foreach ($i in $cur.Items()) {
        $size = $null; $mod = $null
        try { $size = [int64]$i.ExtendedProperty("Size") } catch { }
        try { $mod = $i.ModifyDate.ToString("o") } catch { }
        if (-not $mod) { try { $mod = $i.ExtendedProperty("System.DateModified") } catch { } }
        if ($mod -and $mod -like "1899*") { $mod = $null }
        $items += @{ name = $i.Name; size = $size; modified = $mod }
      }
      Out-Json @{ ok = $true; path = $nav.path; items = $items }
    }
    "copyout" {
      $target = Get-ChildItemByName $cur $Remote
      if (-not $target) { throw "文件不存在：$Remote" }
      $Local = [System.IO.Path]::GetFullPath($Local)
      $destDir = Split-Path -Parent $Local
      if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
      $raw = Join-Path $destDir $Remote
      if (Test-Path $raw) { Remove-Item $raw -Force }
      $dest = $shell.Namespace($destDir)
      if (-not $dest) { throw "本地目录无效：$destDir" }
      $dest.CopyHere($target, 16)
      $last = -1; $stable = 0
      for ($t = 0; $t -lt $TimeoutSec * 10; $t++) {
        if (Test-Path $raw) {
          $len = (Get-Item $raw).Length
          if ($len -gt 0 -and $len -eq $last) { $stable++ } else { $stable = 0 }
          $last = $len
          if ($stable -ge 3) { break }
        }
        Start-Sleep -Milliseconds 100
      }
      if (-not (Test-Path $raw)) { throw "复制失败（MTP 没有返回文件）" }
      if ($raw -ne $Local) { Move-Item $raw $Local -Force }
      Out-Json @{ ok = $true; local = $Local; size = (Get-Item $Local).Length }
    }
    "copyoutmany" {
      $names = @($Remote | ConvertFrom-Json)
      $destDir = [System.IO.Path]::GetFullPath($Local)
      if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
      $dest = $shell.Namespace($destDir)
      $done = @()
      $seen = @{}
      foreach ($i in $cur.Items()) { $seen[$i.Name] = $i }
      foreach ($n in $names) {
        if (-not $seen.ContainsKey($n)) { continue }
        $raw = Join-Path $destDir $n
        if (Test-Path $raw) { Remove-Item $raw -Force }
        $dest.CopyHere($seen[$n], 16)
        $last = -1; $stable = 0
        for ($t = 0; $t -lt 150; $t++) {
          if (Test-Path $raw) {
            $len = (Get-Item $raw).Length
            if ($len -gt 0 -and $len -eq $last) { $stable++ } else { $stable = 0 }
            $last = $len
            if ($stable -ge 2) { break }
          }
          Start-Sleep -Milliseconds 50
        }
        if (Test-Path $raw) { $done += @{ name = $n; size = (Get-Item $raw).Length } }
      }
      Out-Json @{ ok = $true; items = $done; dir = $destDir }
    }
    "verbs" {
      $target = Get-ChildItemByName $cur $Remote
      if (-not $target) { throw "文件不存在：$Remote" }
      $names = @()
      foreach ($v in $target.Verbs()) { $names += $v.Name }
      Out-Json @{ ok = $true; name = $Remote; verbs = $names }
    }
    "delete" {
      $target = Get-ChildItemByName $cur $Remote
      if (-not $target) { Out-Json @{ ok = $true; existed = $false }; break }
      Add-Type -AssemblyName System.Windows.Forms
      $names = @(); foreach ($v in $target.Verbs()) { $names += $v.Name }
      $hit = $names | Where-Object { $_ -match '删除' -or $_ -match 'Delete' } | Select-Object -First 1
      if (-not $hit) { throw "找不到删除命令" }
      $target.InvokeVerb($hit)
      $gone = $false
      for ($t = 0; $t -lt 150; $t++) {
        [System.Windows.Forms.Application]::DoEvents()
        Start-Sleep -Milliseconds 100
        if (-not (Get-ChildItemByName $cur $Remote)) { $gone = $true; break }
      }
      Out-Json @{ ok = $gone; existed = $true }
    }
    "overwrite" {
      $Local = [System.IO.Path]::GetFullPath($Local)
      if (-not (Test-Path $Local)) { throw "本地文件不存在：$Local" }
      $localSize = (Get-Item $Local).Length
      $cur.CopyHere($Local, 1552)
      $ok = $false; $dup = $false
      for ($t = 0; $t -lt 200; $t++) {
        Start-Sleep -Milliseconds 100
        $f = Get-ChildItemByName $cur $Remote
        if ($f) {
          $s = $null
          try { $s = [int64]$f.ExtendedProperty("Size") } catch { }
          if ($s -and $s -eq $localSize) { $ok = $true; break }
        }
      }
      if (-not $ok) {
        $base = [System.IO.Path]::GetFileNameWithoutExtension($Remote)
        $ext = [System.IO.Path]::GetExtension($Remote)
        foreach ($cand in @("$base (2)$ext", "$base(2)$ext")) {
          $d = Get-ChildItemByName $cur $cand
          if ($d) {
            $ds = $null; try { $ds = [int64]$d.ExtendedProperty("Size") } catch { }
            if ($ds -eq $localSize) { $dup = $true; break }
          }
        }
        if ($dup) { throw "手机端保留了原文件、另存为新副本，未覆盖成功" }
        throw "写入后校验失败（大小不一致）"
      }
      Out-Json @{ ok = $true; name = $Remote; size = [int64]$localSize }
    }
    "copyin" {
      $Local = [System.IO.Path]::GetFullPath($Local)
      if (-not (Test-Path $Local)) { throw "本地文件不存在：$Local" }
      $localSize = (Get-Item $Local).Length
      $existing = Get-ChildItemByName $cur $Remote
      if ($existing) {
        Invoke-DeleteVerb $existing
        for ($t = 0; $t -lt 120; $t++) {
          Start-Sleep -Milliseconds 100
          if (-not (Get-ChildItemByName $cur $Remote)) { break }
        }
        if (Get-ChildItemByName $cur $Remote) { throw "无法删除手机上的原文件（可能被 QQ 占用，先退出 QQ 再试）" }
      }
      $cur.CopyHere($Local, 16)
      $ok = $false
      for ($t = 0; $t -lt $TimeoutSec * 10; $t++) {
        Start-Sleep -Milliseconds 200
        $f = Get-ChildItemByName $cur $Remote
        if ($f) {
          $s = $null
          try { $s = [int64]$f.ExtendedProperty("Size") } catch { }
          if ($s -and $s -eq $localSize) { $ok = $true; break }
        }
      }
      if (-not $ok) {
        if (Get-ChildItemByName $cur "$Remote (2)") { throw "手机端拒绝了覆盖，生成了副本 '$Remote (2)'，请手动处理" }
        throw "写入后校验失败（大小不一致），请重试"
      }
      Out-Json @{ ok = $true; name = $Remote; size = [int64]$localSize }
    }
    default { throw "未知操作：$Action" }
  }
}
catch {
  Out-Json @{ ok = $false; error = $_.Exception.Message }
  exit 1
}
