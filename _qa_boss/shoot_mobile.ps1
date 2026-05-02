param(
  [string]$Base = "http://localhost:8082",
  [string]$Out  = "C:\Users\WW\Desktop\sboxmarket\_qa_boss"
)
$chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$routes = @(
  @{ name='m01-home';      path='/' },
  @{ name='m02-market';    path='/market' },
  @{ name='m03-database';  path='/db' },
  @{ name='m04-item-real'; path='/item/1' },
  @{ name='m05-stall-real';path='/stall/1' },
  @{ name='m06-help';      path='/help' },
  @{ name='m07-faq';       path='/faq' },
  @{ name='m08-cart';      path='/cart' },
  @{ name='m09-wallet';    path='/wallet' },
  @{ name='m10-watchlist'; path='/watchlist' },
  @{ name='m11-settings';  path='/settings' },
  @{ name='m12-profile';   path='/profile/personal' },
  @{ name='m13-sell';      path='/sell' },
  @{ name='m14-item-miss'; path='/item/missing' }
)
foreach ($r in $routes) {
  $file = Join-Path $Out ("{0}.png" -f $r.name)
  & $chrome --headless=new --disable-gpu --no-sandbox `
    --window-size=390,852 --hide-scrollbars `
    --user-agent='Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1' `
    --virtual-time-budget=4000 `
    --screenshot=$file ($Base + $r.path) 2>&1 | Out-Null
  if (Test-Path $file) { Write-Host ("OK " + $r.name) } else { Write-Host ("FAIL " + $r.name) }
}
