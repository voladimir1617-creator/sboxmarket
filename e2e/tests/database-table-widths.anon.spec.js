const { test, expect } = require('@playwright/test');

// The /database table had fixed column widths sized for a wide screen, and the
// item column took whatever was left: at 1280 names were cut mid-word with no
// ellipsis, at 1024 the table scrolled sideways and names were 4px wide, and
// from 601 to 820 the rows overlapped. At every width each row must show the
// whole item name, no row may overlap the next, and the table must fit its box.
for (const width of [601, 768, 901, 1024, 1280, 1440]) {
  test(`at ${width}px the database table shows whole item names`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/database');
    const rows = page.locator('.db-table tbody tr.db-row');
    await expect(rows.first()).toBeVisible({ timeout: 15_000 });

    const m = await page.evaluate(() => {
      const scroll = document.querySelector('.db-table-scroll');
      const rows = [...document.querySelectorAll('.db-table tbody tr.db-row')].slice(0, 8);
      const names = rows.map((tr) => {
        const n = tr.querySelector('.db-name');
        const r = n.getBoundingClientRect();
        // A name wraps onto two lines at most; anything hidden past that
        // (or cut sideways) shows as scroll size larger than the box.
        return { text: n.textContent, width: Math.round(r.width),
                 cutSideways: n.scrollWidth > n.clientWidth + 1,
                 cutBelow: n.scrollHeight > n.clientHeight + 1 };
      });
      const overlaps = [];
      for (let i = 1; i < rows.length; i++) {
        const prev = rows[i - 1].getBoundingClientRect();
        for (const td of rows[i - 1].children) {
          const r = td.getBoundingClientRect();
          if (r.height && r.bottom > rows[i].getBoundingClientRect().top + 1) overlaps.push(i);
        }
        if (prev.bottom > rows[i].getBoundingClientRect().top + 1) overlaps.push(i);
      }
      return { sideways: scroll.scrollWidth > scroll.clientWidth + 1, names, overlaps };
    });

    expect(m.sideways, 'table scrolls sideways').toBe(false);
    expect(m.overlaps, 'rows overlap the row below').toEqual([]);
    for (const n of m.names) {
      expect(n.width, `"${n.text}" box width`).toBeGreaterThanOrEqual(80);
      expect(n.cutSideways, `"${n.text}" cut sideways`).toBe(false);
      expect(n.cutBelow, `"${n.text}" cut below two lines`).toBe(false);
    }
  });
}
