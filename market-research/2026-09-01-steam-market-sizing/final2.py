import json, os
import combine
from estimate import quantiles, load_pages

MARKETS = [
    (590830,"s&box",201,1),(730,"CS2",35389,45),(570,"Dota 2",34158,45),
    (440,"TF2",41414,55),(252490,"Rust",5438,7),(304930,"Unturned",9174,12),
    (753,"Steam cards",321473,430),(218620,"PAYDAY 2",3454,5),
    (232090,"Killing Floor 2",2896,4),(322330,"DST",438,1),(578080,"PUBG",357,1),
]
print(f"{'market':<16}{'items':>8}{'listings':>13}{'book value':>15}{'rev if ALL':>12}"
      f"{'avg/txn':>9}{'item med':>10}{'lst med':>9}{'$0 rev':>8}  basis")
out={}
for appid,name,total,stride in MARKETS:
    if not os.path.exists(f"rows_{appid}_pd.jsonl"):
        print(f"{name:<16}{'NOT CAPTURED':>70}"); continue
    c = combine.combined(appid,total,stride)
    has_head = c["head_items"] > 0
    if stride == 1:      basis = "full census"
    elif has_head:       basis = f"1/{stride} + top-{c['head_items']} books"
    else:                basis = f"1/{stride}, listings=LOWER BOUND"
    z = 100*c["zero"]/c["listings"] if c["listings"] else 0
    print(f"{name:<16}{total:>8,}{c['listings']:>13,.0f}${c['book']:>14,.0f}"
          f"${c['rev']:>11,.0f}${c['rev']/c['listings'] if c['listings'] else 0:>8.4f}"
          f"${c['q']['item_med']:>9,.2f}${c['q']['lst_med']:>8,.2f}{z:>7.1f}%  {basis}")
    out[name]={"appid":appid,"items":total,"listings":c["listings"],"book":c["book"],
               "rev_once":c["rev"],"avg_txn":c["rev"]/c["listings"] if c["listings"] else 0,
               "item_median":c["q"]["item_med"],"listing_median":c["q"]["lst_med"],
               "pct_zero":z,"basis":basis}
json.dump(out,open("summary_final.json","w"),indent=1)
