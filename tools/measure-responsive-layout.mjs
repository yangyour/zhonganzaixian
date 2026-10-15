/** Read-only browser layout diagnostics. Findings require visual review, not automatic WCAG signoff. */
export async function measureResponsiveLayout(page) {
  return page.evaluate(() => {
    const availableWidth = document.documentElement.clientWidth
    const visible = el => {
      const style = getComputedStyle(el), rect = el.getBoundingClientRect()
      return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none' && !el.closest('[aria-hidden="true"]')
    }
    const describe = el => `${el.tagName.toLowerCase()}${[...el.classList].slice(0, 3).map(value => '.' + value).join('')}`
    const box = el => { const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height, right: r.right, bottom: r.bottom } }
    const internalScroll = el => {
      for (let parent = el.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
        if (['auto', 'scroll'].includes(getComputedStyle(parent).overflowX) && parent.scrollWidth > parent.clientWidth + 2) return true
      }
      return false
    }
    const controls = [...document.querySelectorAll('#main-content button, #main-content input:not([type="checkbox"]):not([type="radio"]), #main-content select, .app-header button, .topbar-controls button')].filter(visible)
    const outsideControls = controls.filter(el => { const r = el.getBoundingClientRect(); return !internalScroll(el) && (r.left < -2 || r.right > availableWidth + 2) }).map(el => ({ element: describe(el), box: box(el) }))
    const clippedButtonLabels = [], wrappedButtons = []
    for (const button of controls.filter(el => el.tagName === 'BUTTON')) {
      const label = button.querySelector(':scope > span')
      if (!label || !label.textContent?.trim()) continue
      const range = document.createRange(); range.selectNodeContents(label)
      const text = range.getBoundingClientRect(), rect = button.getBoundingClientRect()
      if (text.height > parseFloat(getComputedStyle(label).fontSize) * 1.8) wrappedButtons.push({ element: describe(button), label: label.textContent.trim().slice(0, 60), box: box(button) })
      if (text.top < rect.top - 2 || text.bottom > rect.bottom + 2 || text.left < rect.left - 2 || text.right > rect.right + 2) clippedButtonLabels.push({ element: describe(button), label: label.textContent.trim().slice(0, 60), box: box(button) })
    }
    const overlaps = []
    for (const selector of ['.topbar', '.page-context', '.assets-heading-actions', '.header-actions', '.identity-actions', '.filter-actions']) {
      for (const parent of document.querySelectorAll(selector)) {
        const children = [...parent.children].filter(visible)
        for (let i = 0; i < children.length; i++) for (let j = i + 1; j < children.length; j++) {
          const a = children[i].getBoundingClientRect(), b = children[j].getBoundingClientRect()
          if (Math.min(a.right, b.right) - Math.max(a.left, b.left) > 3 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 3) overlaps.push({ parent: selector, first: describe(children[i]), second: describe(children[j]) })
        }
      }
    }
    const overflowElements = document.documentElement.scrollWidth > availableWidth + 2 ? [...document.querySelectorAll('#main-content *')].filter(el => visible(el) && !internalScroll(el) && el.getBoundingClientRect().right > availableWidth + 2).slice(0, 12).map(el => ({ element: describe(el), box: box(el) })) : []
    const cardAlignment = [...document.querySelectorAll('.assets-kpis, .configuration-summary')].filter(visible).map(grid => {
      const columns = getComputedStyle(grid).gridTemplateColumns.split(' ').length
      const cards = [...grid.children].filter(visible).map(box), unevenRows = []
      for (let i = 0; i < cards.length; i += columns) {
        const row = cards.slice(i, i + columns)
        if (Math.max(...row.map(r => r.y)) - Math.min(...row.map(r => r.y)) > 2 || Math.max(...row.map(r => r.bottom)) - Math.min(...row.map(r => r.bottom)) > 2) unevenRows.push(i / columns)
      }
      return { element: describe(grid), columns, unevenRows, cards }
    })
    return {
      path: location.pathname, width: innerWidth, height: innerHeight,
      availableWidth, documentWidth: document.documentElement.scrollWidth, pageOverflow: document.documentElement.scrollWidth > availableWidth + 2,
      outsideControls, clippedButtonLabels, wrappedButtons, overlaps, overflowElements, cardAlignment,
      heading: document.querySelector('#main-content h1') ? box(document.querySelector('#main-content h1')) : null,
      apiErrorVisible: [...document.querySelectorAll('#main-content .api-error')].some(visible),
    }
  })
}
