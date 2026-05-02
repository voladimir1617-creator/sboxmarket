param(
  [string]$Base = "http://localhost:8082",
  [string]$Out  = "C:\Users\WW\Desktop\sboxmarket\_qa_boss"
)
$chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$routes = @(
  @{ name='01-home';            path='/' },
  @{ name='02-market';          path='/market' },
  @{ name='03-market-grid';     path='/market?view=grid' },
  @{ name='04-database';        path='/db' },
  @{ name='05-help';            path='/help' },
  @{ name='06-faq';             path='/faq' },
  @{ name='07-changelog';       path='/changelog.html' },
  @{ name='08-cookies';         path='/cookies.html' },
  @{ name='09-status';          path='/status.html' },
  @{ name='10-cart';            path='/cart' },
  @{ name='11-watchlist';       path='/watchlist' },
  @{ name='12-wallet';          path='/wallet' },
  @{ name='13-sell';            path='/sell' },
  @{ name='14-settings';        path='/settings' },
  @{ name='15-profile';         path='/profile/personal' },
  @{ name='16-offers';          path='/offers' },
  @{ name='17-buyorders';       path='/buyorders' },
  @{ name='18-mystall';         path='/me/stall' },
  @{ name='19-affiliate';       path='/affiliate' },
  @{ name='20-support';         path='/support' },
  @{ name='21-notifications';   path='/notifications' },
  @{ name='22-item-missing';    path='/item/missing' },
  @{ name='23-stall-missing';   path='/stall/missing' },
  @{ name='24-loadout-missing'; path='/loadout/missing' },
  @{ name='25-item-real';       path='/item/1' },
  @{ name='26-stall-real';      path='/stall/1' },
  @{ name='27-loadout-real';    path='/loadout/3' },
  @{ name='28-admin';           path='/admin' },
  @{ name='29-csr';             path='/csr' },
  @{ name='30-fees';            path='/fees' },
  @{ name='31-pricing';         path='/pricing' },
  @{ name='32-trades';          path='/trades' },
  @{ name='33-deposit-success'; path='/?deposit=success' },
  @{ name='34-not-real-route';  path='/this-route-does-not-exist' }
)

foreach ($r in $routes) {
  $file = Join-Path $Out ("{0}.png" -f $r.name)
  $sep = if ($r.path -match '\?') { '&' } else { '?' }
  $url = $Base + $r.path + $sep + '_qa=1'
  & $chrome --headless=new --disable-gpu --no-sandbox `
    --window-size=1920,1080 --hide-scrollbars `
    --virtual-time-budget=6000 `
    --screenshot=$file $url 2>&1 | Out-Null
  if (Test-Path $file) { Write-Host ("OK " + $r.name) } else { Write-Host ("FAIL " + $r.name) }
}
