/* =========================================================
   shell.js · 应用外壳：导航 / 租户与角色上下文 / 通用组件
   依赖：data.js（DATA）、charts.js（Charts，可选）
   ========================================================= */
(function (global) {
  'use strict';

  /* ---------------- Icons (Lucide 风格, 24x24 stroke) ---------------- */
  const ICONS = {
    star: '<polygon points="12 3 14.8 8.7 21 9.6 16.5 14 17.6 20.2 12 17.3 6.4 20.2 7.5 14 3 9.6 9.2 8.7 12 3"/>',
    'thumbs-up': '<path d="M7 10v11H3V10z M7 10l5-8a3 3 0 0 1 2 4l-1 4h6a2 2 0 0 1 2 2l-2 7a2 2 0 0 1-2 2H7"/>',
    search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
    'chevron-down': '<path d="m6 9 6 6 6-6"/>',
    'chevron-right': '<path d="m9 18 6-6-6-6"/>',
    'chevron-left': '<path d="m15 18-6-6 6-6"/>',
    'chevron-up': '<path d="m18 15-6-6-6 6"/>',
    x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
    check: '<path d="M20 6 9 17l-5-5"/>',
    plus: '<path d="M5 12h14"/><path d="M12 5v14"/>',
    minus: '<path d="M5 12h14"/>',
    bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>',
    dashboard: '<rect width="7" height="9" x="3" y="3" rx="1"/><rect width="7" height="5" x="14" y="3" rx="1"/><rect width="7" height="9" x="14" y="12" rx="1"/><rect width="7" height="5" x="3" y="16" rx="1"/>',
    activity: '<path d="M22 12h-4l-3 9L9 3l-3 9H2"/>',
    server: '<rect width="20" height="8" x="2" y="2" rx="2" ry="2"/><rect width="20" height="8" x="2" y="14" rx="2" ry="2"/><line x1="6" x2="6.01" y1="6" y2="6"/><line x1="6" x2="6.01" y1="18" y2="18"/>',
    plug: '<path d="M12 22v-5"/><path d="M9 8V2"/><path d="M15 8V2"/><path d="M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8Z"/>',
    layers: '<path d="m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z"/><path d="m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65"/><path d="m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65"/>',
    router: '<rect width="20" height="8" x="2" y="14" rx="2"/><path d="M6.01 18H6"/><path d="M10.01 18H10"/><path d="M15 10v4"/><path d="M17.84 7.17a4 4 0 0 0-5.66 0"/><path d="M20.66 4.34a8 8 0 0 0-11.31 0"/>',
    'alert-triangle': '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
    info: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>',
    sliders: '<line x1="21" x2="14" y1="4" y2="4"/><line x1="10" x2="3" y1="4" y2="4"/><line x1="21" x2="12" y1="12" y2="12"/><line x1="8" x2="3" y1="12" y2="12"/><line x1="21" x2="16" y1="20" y2="20"/><line x1="12" x2="3" y1="20" y2="20"/><line x1="14" x2="14" y1="2" y2="6"/><line x1="8" x2="8" y1="10" y2="14"/><line x1="16" x2="16" y1="18" y2="22"/>',
    send: '<path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/>',
    ticket: '<path d="M2 9a3 3 0 0 1 0 6v2a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-2a3 3 0 0 1 0-6V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2Z"/><path d="M13 5v2"/><path d="M13 17v2"/><path d="M13 11v2"/>',
    zap: '<path d="M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"/>',
    'bar-chart': '<path d="M3 3v18h18"/><path d="M18 17V9"/><path d="M13 17V5"/><path d="M8 17v-3"/>',
    'pie-chart': '<path d="M21.21 15.89A10 10 0 1 1 8 2.83"/><path d="M22 12A10 10 0 0 0 12 2v10z"/>',
    users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    user: '<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
    'file-text': '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M10 9H8"/><path d="M16 13H8"/><path d="M16 17H8"/>',
    building: '<path d="M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z"/><path d="M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2"/><path d="M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2"/><path d="M10 6h4"/><path d="M10 10h4"/><path d="M10 14h4"/><path d="M10 18h4"/>',
    database: '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5V19A9 3 0 0 0 21 19V5"/><path d="M3 12A9 3 0 0 0 21 12"/>',
    calculator: '<rect width="16" height="20" x="4" y="2" rx="2"/><line x1="8" x2="16" y1="6" y2="6"/><line x1="16" x2="16" y1="14" y2="18"/><path d="M16 10h.01"/><path d="M12 10h.01"/><path d="M8 10h.01"/><path d="M12 14h.01"/><path d="M8 14h.01"/><path d="M12 18h.01"/><path d="M8 18h.01"/>',
    'heart-pulse': '<path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"/><path d="M3.22 12H9.5l.5-1 2 4.5 2-7 1.5 3.5h5.27"/>',
    clock: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    settings: '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>',
    'log-out': '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>',
    'external-link': '<path d="M15 3h6v6"/><path d="M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>',
    filter: '<polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/>',
    download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/>',
    upload: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" x2="12" y1="3" y2="15"/>',
    refresh: '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>',
    more: '<circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/>',
    lock: '<rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
    eye: '<path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/>',
    cpu: '<rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6"/><path d="M15 2v2"/><path d="M15 20v2"/><path d="M2 15h2"/><path d="M2 9h2"/><path d="M20 15h2"/><path d="M20 9h2"/><path d="M9 2v2"/><path d="M9 20v2"/>',
    'hard-drive': '<line x1="22" x2="2" y1="12" y2="12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/><line x1="6" x2="6.01" y1="16" y2="16"/><line x1="10" x2="10.01" y1="16" y2="16"/>',
    network: '<rect x="16" y="16" width="6" height="6" rx="1"/><rect x="2" y="16" width="6" height="6" rx="1"/><rect x="9" y="2" width="6" height="6" rx="1"/><path d="M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3"/><path d="M12 12V8"/>',
    globe: '<circle cx="12" cy="12" r="10"/><path d="M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20"/><path d="M2 12h20"/>',
    terminal: '<polyline points="4 17 10 11 4 5"/><line x1="12" x2="20" y1="19" y2="19"/>',
    'git-branch': '<line x1="6" x2="6" y1="3" y2="15"/><circle cx="18" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M18 9a9 9 0 0 1-9 9"/>',
    copy: '<rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
    'map-pin': '<path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z"/><circle cx="12" cy="10" r="3"/>',
    calendar: '<rect width="18" height="18" x="3" y="4" rx="2" ry="2"/><line x1="16" x2="16" y1="2" y2="6"/><line x1="8" x2="8" y1="2" y2="6"/><line x1="3" x2="21" y1="10" y2="10"/>',
    message: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
    edit: '<path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/>',
    trash: '<path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/>',
    pause: '<rect width="4" height="16" x="6" y="4"/><rect width="4" height="16" x="14" y="4"/>',
    play: '<polygon points="6 3 20 12 6 21 6 3"/>',
    book: '<path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>',
    link: '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>',
    key: '<circle cx="7.5" cy="15.5" r="5.5"/><path d="m21 2-9.6 9.6"/><path d="m15.5 7.5 3 3L22 7l-3-3"/>',
    mail: '<rect width="20" height="16" x="2" y="4" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/>',
    phone: '<rect width="14" height="20" x="5" y="2" rx="2" ry="2"/><path d="M12 18h.01"/>',
    hash: '<line x1="4" x2="20" y1="9" y2="9"/><line x1="4" x2="20" y1="15" y2="15"/><line x1="10" x2="8" y1="3" y2="21"/><line x1="16" x2="14" y1="3" y2="21"/>',
    shield: '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="m9 12 2 2 4-4"/>',
    'arrow-up-right': '<path d="M7 7h10v10"/><path d="M7 17 17 7"/>',
    'arrow-right': '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
    'arrow-left': '<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
    'check-circle': '<path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="m9 11 3 3L22 4"/>',
    'x-circle': '<circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/>',
    wrench: '<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>',
    box: '<path d="m7.5 4.27 9 5.15"/><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/>',
    monitor: '<rect width="20" height="14" x="2" y="3" rx="2"/><line x1="8" x2="16" y1="21" y2="21"/><line x1="12" x2="12" y1="17" y2="21"/>',
    gauge: '<path d="m12 14 4-4"/><path d="M3.34 19a10 10 0 1 1 17.32 0"/>',
    history: '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/>',
    grid: '<rect width="7" height="7" x="3" y="3" rx="1"/><rect width="7" height="7" x="14" y="3" rx="1"/><rect width="7" height="7" x="14" y="14" rx="1"/><rect width="7" height="7" x="3" y="14" rx="1"/>',
    list: '<line x1="8" x2="21" y1="6" y2="6"/><line x1="8" x2="21" y1="12" y2="12"/><line x1="8" x2="21" y1="18" y2="18"/><line x1="3" x2="3.01" y1="6" y2="6"/><line x1="3" x2="3.01" y1="12" y2="12"/><line x1="3" x2="3.01" y1="18" y2="18"/>',
    inbox: '<polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
    cloud: '<path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"/>',
    thermometer: '<path d="M14 4v10.54a4 4 0 1 1-4 0V4a2 2 0 0 1 4 0Z"/>',
    power: '<path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.77.04"/>',
    workflow: '<rect width="8" height="8" x="3" y="3" rx="2"/><path d="M7 11v4a2 2 0 0 0 2 2h4"/><rect width="8" height="8" x="13" y="13" rx="2"/>',
    home: '<path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/>',
    wifi: '<path d="M12 20h.01"/><path d="M2 8.82a15 15 0 0 1 20 0"/><path d="M5 12.859a10 10 0 0 1 14 0"/><path d="M8.5 16.429a5 5 0 0 1 7 0"/>',
    tag: '<path d="M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z"/><circle cx="7.5" cy="7.5" r=".5" fill="currentColor"/>',
    layout: '<rect width="18" height="18" x="3" y="3" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/>',
    bot: '<path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/>',
    timer: '<line x1="10" x2="14" y1="2" y2="2"/><line x1="12" x2="15" y1="14" y2="11"/><circle cx="12" cy="14" r="8"/>',
    scale: '<path d="m16 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/><path d="m2 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/><path d="M7 21h10"/><path d="M12 3v18"/><path d="M3 7h2c2 0 5-1 7-2 2 1 5 2 7 2h2"/>',
    sparkles: '<path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/>',
    split: '<path d="M16 3h5v5"/><path d="M8 3H3v5"/><path d="M12 22v-8.3a4 4 0 0 0-1.172-2.872L3 3"/><path d="m15 9 6-6"/>'
  };
  function icon(name, cls) {
    const p = ICONS[name] || ICONS.info;
    return `<svg class="ic ${cls || ''}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${p}</svg>`;
  }
  function emptyIllustration() {
    return `<svg class="empty-art" viewBox="0 0 320 205" aria-hidden="true"><defs><linearGradient id="emptyFade" x2="0" y2="1"><stop stop-color="#edf1fc"/><stop offset="1" stop-color="#fafcff"/></linearGradient><linearGradient id="emptyBox" x2="1" y2="1"><stop stop-color="#c8d9f7"/><stop offset="1" stop-color="#edf3ff"/></linearGradient></defs><path fill="url(#emptyFade)" d="M44 68h34v111H44zM115 13h38v166h-38zM177 34h42v145h-42zM240 76h35v103h-35z"/><ellipse cx="166" cy="178" rx="133" ry="16" fill="#edf5fd"/><path d="m118 112 58-23 59 26-2 53-58 20-58-24z" fill="url(#emptyBox)"/><path d="m176 114 58 1-59 20-58-23 59-23z" fill="#d4e0f4"/><path d="m117 112-17-22 58-16 18 15zm59-23 18-18 63 23-23 21z" fill="#e9eef9"/><path d="m176 135 58-20-15 25-43 13zm0 0-59-23 13 27 46 14z" fill="#d4dff3"/><path d="m176 135-1 53" stroke="#b9cce8"/><circle cx="105" cy="91" r="8" fill="#f2d5c7"/><path d="m97 87 6-7 10 2 1 8-6-1-6 5" fill="#424b6e"/><path d="m101 101 13-3 10 22-13 15-17-6z" fill="#4882f6"/><path d="m102 130 14 4-7 27-14 17-5-4 8-23zm12 3 9 15-5 29-6-1 2-24-10-12" fill="#3c568e"/><path d="m113 103 12 8 14-2 1 4-17 5-14-9" stroke="#eac6b8" stroke-width="5" fill="none"/><path d="m272 146 10 33h-19z" fill="#8cd5bb"/><path d="m90 175 8 2-7 4h-7zm22 1 9 1 3 4h-13z" fill="#35456e"/></svg>`;
  }

  /* ---------------- 基础工具 ---------------- */
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const qs = (k, d) => { const v = new URLSearchParams(location.search).get(k); return v == null ? d : v; };
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const uid = () => Math.random().toString(36).slice(2, 8);
  const pad = (n) => String(n).padStart(2, '0');

  const fmt = {
    n(v, d = 0) { if (v == null || isNaN(v)) return '—'; return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d }); },
    pct(v, d = 1) { if (v == null || isNaN(v)) return '—'; return fmt.n(v, d) + '%'; },
    compact(v) { if (v == null) return '—'; const a = Math.abs(v); if (a >= 1e9) return fmt.n(v / 1e9, 2) + 'B'; if (a >= 1e6) return fmt.n(v / 1e6, 2) + 'M'; if (a >= 1e3) return fmt.n(v / 1e3, 1) + 'k'; return fmt.n(v, a < 10 && a !== Math.floor(a) ? 1 : 0); },
    bytes(v, d = 1) { if (v == null || isNaN(v)) return '—'; const u = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']; let i = 0; let x = Number(v); while (x >= 1024 && i < u.length - 1) { x /= 1024; i++; } return fmt.n(x, x >= 100 ? 0 : d) + ' ' + u[i]; },
    bps(v, d = 1) { if (v == null || isNaN(v)) return '—'; const u = ['bps', 'Kbps', 'Mbps', 'Gbps', 'Tbps']; let i = 0; let x = Number(v); while (x >= 1000 && i < u.length - 1) { x /= 1000; i++; } return fmt.n(x, x >= 100 ? 0 : d) + ' ' + u[i]; },
    rate(v, unit = '/s', d = 0) { return fmt.n(v, d) + ' ' + unit; },
    dur(sec) {
      if (sec == null || isNaN(sec)) return '—'; sec = Math.max(0, Math.round(sec));
      const d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
      if (d > 0) return `${d}d ${h}h`; if (h > 0) return `${h}h ${m}m`; if (m > 0) return `${m}m ${s}s`; return `${s}s`;
    },
    ago(ts) {
      const diff = Math.max(0, (DATA.now - ts) / 1000);
      if (diff < 60) return `${Math.round(diff)} 秒前`; if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`; if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`; return `${Math.floor(diff / 86400)} 天前`;
    },
    time(ts) { const d = new Date(ts); return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`; },
    hm(ts) { const d = new Date(ts); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; },
    dt(ts) { const d = new Date(ts); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`; },
    date(ts) { const d = new Date(ts); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; },
    ms(v) { return v >= 1000 ? fmt.n(v / 1000, 2) + ' s' : fmt.n(v, 0) + ' ms'; }
  };

  /* ---------------- 语义映射 ---------------- */
  const SEV = {
    disaster: { name: '灾难', cls: 'disaster', color: 'var(--sev-disaster)', rank: 5 },
    high: { name: '严重', cls: 'high', color: 'var(--sev-high)', rank: 4 },
    average: { name: '一般', cls: 'average', color: 'var(--sev-average)', rank: 3 },
    warning: { name: '警告', cls: 'warning', color: 'var(--sev-warning)', rank: 2 },
    info: { name: '信息', cls: 'info', color: 'var(--sev-info)', rank: 1 },
    none: { name: '未分类', cls: 'none', color: 'var(--sev-none)', rank: 0 }
  };
  const HSTATE = {
    NEW: { name: '新建', cls: 'new' }, ACKNOWLEDGED: { name: '已确认', cls: 'ack' }, IN_PROGRESS: { name: '处理中', cls: 'prog' },
    RESOLVED: { name: '已解决', cls: 'res' }, CLOSED: { name: '已关闭', cls: 'closed' }
  };
  const HFLOW = ['NEW', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];
  const SYNC = { active: ['ok', '生效'], syncing: ['sync', '同步中'], failed: ['danger', '同步失败'], pending: ['unknown', '待接入'], drift: ['warn', '配置漂移'] };
  const AVAIL = { ok: ['ok', '正常'], problem: ['danger', '有问题'], unknown: ['unknown', '未知'], maintenance: ['maint', '维护中'], disabled: ['neutral', '已停用'] };
  const TYPES = {
    linux: { name: 'Linux 服务器', icon: 'server' }, windows: { name: 'Windows 服务器', icon: 'monitor' }, switch: { name: '交换机', icon: 'network' },
    firewall: { name: '防火墙', icon: 'shield' }, mysql: { name: 'MySQL 数据库', icon: 'database' }, pgsql: { name: 'PostgreSQL 数据库', icon: 'database' },
    web: { name: 'Web 业务', icon: 'globe' }, middleware: { name: '中间件', icon: 'box' }, custom: { name: '自定义指标', icon: 'terminal' }
  };
  const ROLES = {
    viewer: { name: '客户观察者', short: '观察者', desc: '授权范围只读；告警 / 工单只读，可评论' },
    tadmin: { name: '客户管理员', short: '客户管理员', desc: '本租户资产、接入配置、确认与派单、通知策略；选择已批准模板并申请执行' },
    msp: { name: 'MSP 值班工程师', short: '值班工程师', desc: '被指派租户范围；确认、处置、升级、复核；执行被授权 Runbook' },
    padmin: { name: '平台管理员', short: '平台管理员', desc: '全局管理需独立授权；版本发布、路由、配额；高风险执行审批职责分开' }
  };
  const USERS = {
    viewer: { name: '王芸', initials: 'W', title: '客户观察者 · 华东智造 IT' },
    tadmin: { name: '陈立', initials: 'C', title: '客户管理员 · 华东智造 IT 经理' },
    msp: { name: '张伟', initials: 'Z', title: 'MSP 值班工程师 · L1' },
    padmin: { name: '刘岚', initials: 'L', title: '平台管理员' }
  };

  const sevBadge = (s, extra) => { const m = SEV[s] || SEV.none; return `<span class="sev ${m.cls} ${extra || ''}">${m.name}</span>`; };
  const sevName = (s) => (SEV[s] || SEV.none).name;
  const sevColor = (s) => (SEV[s] || SEV.none).color;
  const statePill = (s) => { const m = HSTATE[s] || { name: s, cls: '' }; return `<span class="state-pill ${m.cls}">${m.name}</span>`; };
  const srcBadge = (s) => s === 'PROBLEM' ? `<span class="badge danger"><span class="dot danger pulse"></span>PROBLEM</span>` : `<span class="badge ok"><span class="dot ok"></span>RECOVERED</span>`;
  const badge = (kind, text, dot) => `<span class="badge ${kind}">${dot ? `<span class="dot ${kind}"></span>` : ''}${text}</span>`;
  const syncBadge = (s) => { const m = SYNC[s] || SYNC.pending; return badge(m[0], m[1], true); };
  const availBadge = (s) => { const m = AVAIL[s] || AVAIL.unknown; return badge(m[0], m[1], true); };
  const typeIcon = (t) => { const m = TYPES[t] || TYPES.custom; return `<span class="type-icon ${t}">${icon(m.icon)}</span>`; };
  const typeName = (t) => (TYPES[t] || TYPES.custom).name;
  const avatar = (name, color) => `<span class="avatar-sm" style="background:${color || '#64748b'}">${esc(String(name || '?').slice(0, 1))}</span>`;
  const meter = (pct, cls, thick) => { const c = cls || (pct >= 90 ? 'danger' : pct >= 75 ? 'warn' : 'ok'); return `<div class="meter ${c} ${thick ? 'thick' : ''}"><i style="width:${clamp(pct, 0, 100)}%"></i></div>`; };
  const levelBadge = (l) => ({ 1: '<span class="level l1">只读诊断</span>', 2: '<span class="level l2">低风险修复</span>', 3: '<span class="level l3">高风险变更</span>' })[l] || '';

  /* ---------------- 状态（租户 / 角色）---------------- */
  const store = {
    get(k, d) { try { const v = localStorage.getItem('msp.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem('msp.' + k, JSON.stringify(v)); } catch (e) { } }
  };
  const state = { role: store.get('role', 'padmin'), tenant: store.get('tenant', 'all') };
  const topologyAudit = store.get('network-topology.audit', []);
  if (Array.isArray(topologyAudit)) DATA.audit.unshift(...topologyAudit.filter(r => r && r.action === 'network_topology.save').slice(0, 100));
  // 允许通过 URL 参数预设角色 / 租户（用于原型导航深链接）：?role=viewer&tenant=t-huadong
  const qsRole = new URLSearchParams(location.search).get('role'), qsTenant = new URLSearchParams(location.search).get('tenant');
  if (qsRole && ROLES[qsRole]) { state.role = qsRole; store.set('role', qsRole); }
  if (qsTenant && (qsTenant === 'all' || DATA.tenants.find(t => t.id === qsTenant))) { state.tenant = qsTenant; store.set('tenant', qsTenant); }
  if (!ROLES[state.role]) state.role = 'padmin';
  function tenantAllowed(role) { return role === 'msp' || role === 'padmin'; }
  function normalizeTenant() { if (!tenantAllowed(state.role)) state.tenant = DATA.tenants[0].id; if (state.tenant !== 'all' && !DATA.tenants.find(t => t.id === state.tenant)) state.tenant = 'all'; }
  normalizeTenant();

  const tenant = () => state.tenant === 'all' ? { id: 'all', name: '全部租户', short: '全部', plan: '跨租户视图', color: 'all', pool: '—', isolation: '—' } : DATA.tenants.find(t => t.id === state.tenant);
  const tenantScope = () => state.tenant === 'all' ? DATA.tenants.map(t => t.id) : [state.tenant];
  const inScope = (r) => state.tenant === 'all' || r.tenant === state.tenant;
  const tenantName = (id) => (DATA.tenants.find(t => t.id === id) || {}).name || id;
  const tenantObj = (id) => DATA.tenants.find(t => t.id === id);
  const can = (...roles) => roles.includes(state.role);

  /* ---------------- 导航 ---------------- */
  const ALL = ['viewer', 'tadmin', 'msp', 'padmin'];
  const OPS = ['tadmin', 'msp', 'padmin'];
  const MSP = ['msp', 'padmin'];
  const PAD = ['padmin'];
  const NAV = [
    { label: '首页', items: [
      { id: 'workbench', label: '我的待办', icon: 'list', href: 'index.html', roles: ALL },
      { id: 'dashboard', label: '总览看板', icon: 'dashboard', href: 'dashboard.html', roles: ALL },
      { id: 'oncall', label: '值班看板', icon: 'activity', href: 'oncall.html', roles: MSP }
    ] },
    { label: '监控', items: [
      { id: 'network-topology', label: '网络拓扑', icon: 'network', href: 'network-topology.html', roles: ALL },
      { id: 'templates', label: '监控模板', icon: 'layers', href: 'templates.html', roles: OPS },
      { id: 'proxies', label: '站点与 Proxy', icon: 'router', href: 'proxies.html', roles: OPS }
    ] },
    { label: '告警', items: [
      { id: 'incidents', label: '事件中心', icon: 'alert-triangle', href: 'incidents.html', roles: ALL, sub: ['incident-detail'], countKey: 'openIncidents' },
      { id: 'alert-rules', label: '告警策略', icon: 'sliders', href: 'alert-rules.html', roles: OPS },
      { id: 'notifications', label: '通知渠道', icon: 'send', href: 'notifications.html', roles: OPS },
    ] },
    { label: '远程处置', items: [
      { id: 'remote', label: '远程会话', icon: 'monitor', href: 'remote.html', roles: MSP },
      { id: 'automation', label: '自动化增强', icon: 'zap', href: 'automation.html', roles: OPS, countKey: 'pendingApprovals' }
    ] },
    { label: '资产', items: [
      { id: 'assets', label: '资产管理', icon: 'server', href: 'assets.html', roles: ALL, sub: ['asset-detail'] },
      { id: 'onboarding', label: '资产接入', icon: 'plug', href: 'onboarding.html', roles: OPS },
      { id: 'backups', label: '配置备份', icon: 'database', href: 'backups.html', roles: ALL }
    ] },
    { label: 'AI 知识库', items: [
      { id: 'knowledge', label: '文章中心', icon: 'book', href: 'knowledge.html', roles: ALL },
      { id: 'knowledge-ai', label: 'AI 检索问答', icon: 'sparkles', href: 'knowledge-ai.html', roles: ALL }
    ] },
    { label: '服务台', items: [
      { id: 'tickets', label: '工单', icon: 'ticket', href: 'tickets.html', roles: ALL },
      { id: 'portal', label: '客户门户', icon: 'phone', href: 'portal.html', roles: ALL }
    ] },
    { label: '运营分析', items: [
      { id: 'sla', label: 'SLA 报表', icon: 'bar-chart', href: 'sla.html', roles: ALL },
      { id: 'reports', label: '报告中心', icon: 'file-text', href: 'reports.html', roles: ALL },
      { id: 'operations', label: '运营看板', icon: 'pie-chart', href: 'operations.html', roles: MSP },
    ] },
    { label: '管理', items: [
      { id: 'members', label: '成员与权限', icon: 'users', href: 'members.html', roles: ['tadmin', 'padmin'] },
      { id: 'audit', label: '审计日志', icon: 'file-text', href: 'audit.html', roles: OPS },
      { id: 'tenants', label: '租户管理', icon: 'building', href: 'tenants.html', roles: PAD },
      { id: 'instances', label: '监控资源池', icon: 'database', href: 'instances.html', roles: PAD },
      { id: 'capacity', label: '容量估算', icon: 'calculator', href: 'capacity.html', roles: PAD },
      { id: 'platform-health', label: '平台健康', icon: 'heart-pulse', href: 'platform-health.html', roles: PAD }
    ] }
  ];
  function findNav(pageId) {
    for (const g of NAV) for (const it of g.items) if (it.id === pageId || (it.sub || []).includes(pageId)) return { group: g, item: it };
    return null;
  }
  function navHref(href) {
    const url = new URL(href, location.href);
    url.searchParams.set('role', state.role);
    url.searchParams.set('tenant', state.tenant);
    return url.pathname.split('/').pop() + url.search;
  }

  /* ---------------- 外壳渲染 ---------------- */
  const App = { icon, emptyIllustration, $, $$, esc, qs, fmt, clamp, uid, SEV, HSTATE, HFLOW, ROLES, USERS, TYPES, NAV, state, sevBadge, sevName, sevColor, statePill, srcBadge, badge, syncBadge, availBadge, typeIcon, typeName, avatar, meter, levelBadge, tenant, tenantScope, inScope, tenantName, tenantObj, can, store };
  let current = null;
  let freshTimer = null;

  function counts() {
    const inc = DATA.incidents.filter(inScope).filter(i => i.sourceState === 'PROBLEM' && i.handlingState !== 'CLOSED').length;
    const appr = DATA.approvals.filter(inScope).filter(a => a.status === 'pending').length;
    return { openIncidents: inc, pendingApprovals: appr };
  }

  function sidebarHtml(pageId) {
    const t = tenant(); const c = counts(); const nav = findNav(pageId);
    let navHtml = '';
    for (const g of NAV.filter(g => g === (nav ? nav.group : NAV[0]))) {
      if (g.roles && !g.roles.includes(state.role)) continue;
      const items = g.items.filter(it => it.roles.includes(state.role));
      if (!items.length) continue;
      navHtml += `<div class="nav-group"><div class="nav-label">${g.label}</div>` + items.map(it => {
        const active = nav && nav.item.id === it.id;
        const cnt = it.countKey && c[it.countKey] ? `<span class="count ${it.countKey === 'pendingApprovals' ? 'muted' : ''}">${c[it.countKey]}</span>` : '';
        return `<a href="${navHref(it.href)}" class="${active ? 'active' : ''}" ${active ? 'aria-current="page"' : ''}>${icon(it.icon)}<span>${it.label}</span>${cnt}</a>`;
      }).join('') + '</div>';
    }
    return `<aside class="sidebar">
      <nav class="nav" aria-label="二级菜单">${navHtml}</nav>
      <div class="sidebar-foot"><span>交互演示</span><a href="prototype-map.html" title="查看页面导航">全部页面 ${icon('external-link', '')}</a></div>
    </aside>`;
  }

  function topbarHtml(pageId, title) {
    const nav = findNav(pageId);
    const crumbs = nav ? `<span>${nav.group.label}</span><span class="sep">›</span>${nav.item.id !== pageId ? `<a href="${nav.item.href}">${nav.item.label}</a><span class="sep">›</span>` : ''}<b>${esc(title || nav.item.label)}</b>` : `<b>${esc(title || '')}</b>`;
    return `<header class="topbar">
      <button class="sidebar-toggle" id="sidebarToggle" aria-label="收起侧栏" aria-expanded="true">${icon('chevron-left')}</button><div class="crumbs">${crumbs}</div>
      <div class="search"><input id="globalSearch" placeholder="搜索资产 / 事件 / 工单 / Runbook…" autocomplete="off">${icon('search')}<kbd>/</kbd><div class="search-pop" id="searchPop"></div></div>
      <div class="context-controls">${tenantSwitchHtml()}<span class="fresh">数据 <span id="freshSec">3</span> 秒前</span><label class="role-select" title="演示角色">${icon('eye')}<select id="roleSelect" aria-label="角色">${Object.entries(ROLES).map(([k,v])=>`<option value="${k}" ${k===state.role?'selected':''}>${v.name}</option>`).join('')}</select></label></div>
    </header>`;
  }

  function tenantSwitchHtml() {
    const t = tenant(), color = t.id === 'all' ? 'tenant-color-all' : 'tenant-color-' + t.color;
    if (!tenantAllowed(state.role)) return `<span class="customer-context">${icon('building')} ${esc(t.name)}</span>`;
    return `<div class="header-tenant"><button type="button" class="tenant-switch" id="tenantSwitch" title="切换当前租户（操作将被审计）" aria-label="切换客户" aria-expanded="false"><span class="tenant-avatar ${color}">${esc(t.short.slice(0, 1))}</span><span class="tenant-meta"><span class="tenant-name">${esc(t.name)}</span><span class="tenant-plan">${t.id === 'all' ? '跨租户视图' : `${esc(t.plan)} · ${t.isolation === 'dedicated' ? '独享池' : t.isolation === 'onprem' ? '独立部署' : '共享池'}`}</span></span><span class="chev">${icon('chevron-down')}</span></button><div class="pop tenant-pop" id="tenantPop"></div></div>`;
  }

  function headerActionsHtml() {
    const u = USERS[state.role];
    const roleOpts = Object.entries(ROLES).map(([k, v]) => `<option value="${k}" ${k === state.role ? 'selected' : ''}>${v.name}</option>`).join('');
    return `<div class="topbar-right"><a class="icon-btn app-launcher" href="${navHref('index.html')}" aria-label="工作台">${icon('grid')}</a><div style="position:relative"><button class="icon-btn" id="bellBtn" title="通知" aria-label="通知">${icon('bell')}<span class="pin"></span></button><div class="pop bell-pop" id="bellPop"></div></div><button type="button" class="user-chip" id="userChip" aria-label="当前用户会话" title="${esc(u.name + ' · ' + u.title)}"><span class="avatar">${icon('user')}</span><span class="user-info"><span class="u-name">${u.name}</span></span>${icon('chevron-down')}</button></div>`;
  }

  function primaryNavHtml(pageId) {
    const currentNav = findNav(pageId);
    const links = NAV.filter(g => (!g.roles || g.roles.includes(state.role)) && g.items.some(it => it.roles.includes(state.role))).map((g, index) => {
      const first = g.items.find(it => it.roles.includes(state.role));
      const active = currentNav && currentNav.group === g;
      return `<a href="${navHref(first.href)}" class="primary-nav-link ${active ? 'active' : ''}" ${active ? 'aria-current="page"' : ''}><span class="module-icon module-${index}">${icon(first.icon)}</span><span>${g.label}</span></a>`;
    }).join('');
    return `<header class="primary-header"><a class="primary-brand" href="${navHref('index.html')}"><div class="brand-mark">M</div><div><div class="brand-name">MOP 运维服务平台</div><div class="brand-sub">众安在线 · 托管运维</div></div></a><nav class="primary-nav" aria-label="一级菜单">${links}</nav>${headerActionsHtml()}</header>`;
  }

  function mountShell(pageId, title) {
    const root = document.getElementById('app');
    root.innerHTML = `<div class="app">${primaryNavHtml(pageId)}${sidebarHtml(pageId)}<div class="main">${topbarHtml(pageId, title)}<main class="content" id="content"></main></div></div>
      <div class="toasts" id="toasts"></div>
      <div class="proto-note">交互演示 · 本地模拟数据</div>`;
    document.body.classList.toggle('sidebar-collapsed', !!store.get('sidebar-collapsed', false));
    bindShell();
  }

  function bindShell() {
    // 租户切换
    const ts = $('#tenantSwitch'), tp = $('#tenantPop');
    if (ts && tp) {
    tp.innerHTML = `<div class="pop-label">切换租户上下文</div>` + (tenantAllowed(state.role) ? `<div class="pop-item ${state.tenant === 'all' ? 'active' : ''}" data-t="all"><span class="tenant-avatar tenant-color-all" style="width:24px;height:24px;font-size:11px;border-radius:6px">全</span>全部租户<small>跨租户</small></div><div class="pop-sep"></div>` : '') +
      DATA.tenants.filter(t => tenantAllowed(state.role) || t.id === state.tenant || t.id === DATA.tenants[0].id).map(t => `<div class="pop-item ${state.tenant === t.id ? 'active' : ''}" data-t="${t.id}"><span class="tenant-avatar tenant-color-${t.color}" style="width:24px;height:24px;font-size:11px;border-radius:6px">${esc(t.short.slice(0, 1))}</span>${esc(t.name)}<small>${t.isolation === 'dedicated' ? '独享' : t.isolation === 'onprem' ? '独立部署' : '共享'} · ${t.assets}</small></div>`).join('') +
      `<div class="pop-sep"></div><div class="pop-item" style="font-size:11.5px;color:var(--text-4)">${icon('shield')} 切换将写入审计日志（tenant_switch）</div>`;
    ts.onclick = (e) => { e.stopPropagation(); tp.classList.toggle('open'); ts.setAttribute('aria-expanded', String(tp.classList.contains('open'))); };
    tp.onclick = (e) => {
      const it = e.target.closest('[data-t]'); if (!it) return;
      const id = it.dataset.t; if (id === state.tenant) { tp.classList.remove('open'); return; }
      state.tenant = id; store.set('tenant', id); tp.classList.remove('open');
      const trace = uid();
      DATA.audit.unshift({ id: 'al-' + uid(), ts: DATA.now, actor: USERS[state.role].name, role: state.role, tenant: id === 'all' ? 'platform' : id, action: 'tenant_switch', object: id === 'all' ? '全部租户' : tenantName(id), result: 'success', ip: '10.20.8.41', trace });
      rerender();
      toast(`已切换至「${id === 'all' ? '全部租户' : tenantName(id)}」· 已记录审计 trace ${trace}`, 'info');
    };
    }
    const toggle=$('#sidebarToggle');
    const syncCollapse=()=>{const collapsed=document.body.classList.contains('sidebar-collapsed');toggle.setAttribute('aria-label',collapsed?'展开侧栏':'收起侧栏');toggle.setAttribute('aria-expanded',String(!collapsed));};
    toggle.onclick=()=>{document.body.classList.toggle('sidebar-collapsed');store.set('sidebar-collapsed',document.body.classList.contains('sidebar-collapsed'));syncCollapse();window.dispatchEvent(new Event('resize'));};syncCollapse();
    // 角色切换
    $('#roleSelect').onchange = (e) => { state.role = e.target.value; store.set('role', state.role); normalizeTenant(); store.set('tenant', state.tenant); rerender(); toast(`当前以「${ROLES[state.role].name}」视角浏览：${ROLES[state.role].desc}`, 'warn'); };
    // 通知铃铛
    const bb = $('#bellBtn'), bp = $('#bellPop');
    const recent = DATA.incidents.filter(inScope).filter(i => i.sourceState === 'PROBLEM' && i.handlingState === 'NEW').sort((a, b) => b.sourceTime - a.sourceTime).slice(0, 5);
    bp.innerHTML = `<div class="pop-head"><span>未确认告警 (${recent.length})</span><a href="incidents.html" style="font-weight:500;font-size:12px">查看全部</a></div>` + (recent.length ? recent.map(i => `<div class="bell-item" onclick="location.href='incident-detail.html?id=${i.id}'"><span class="sevbar ${i.severity}"></span><div class="grow"><div class="strong ellipsis">${esc(i.name)}</div><div class="bi-t">${esc(i.assetName)} · ${tenantName(i.tenant)} · ${fmt.ago(i.sourceTime)}</div></div></div>`).join('') : `<div class="empty small">当前范围内没有未确认告警</div>`);
    if (!recent.length) $('.pin', bb).remove();
    bb.onclick = (e) => { e.stopPropagation(); bp.classList.toggle('open'); };
    // 用户菜单
    $('#userChip').onclick = () => modal({ title: '当前会话', body: `<dl class="kv"><dt>用户</dt><dd>${USERS[state.role].name}</dd><dt>角色</dt><dd>${ROLES[state.role].name}</dd><dt>登录方式</dt><dd>OIDC · 企业身份提供方 · MFA 已验证</dd><dt>租户成员</dt><dd>${tenantAllowed(state.role) ? '跨租户授权（被指派范围）' : tenantName(state.tenant)}</dd><dt>授权版本</dt><dd class="mono">perm-v184 · 撤权后即时失效</dd><dt>会话</dt><dd>剩余 7 小时 42 分 · IP 10.20.8.41</dd></dl>`, foot: `<a class="btn" href="login.html">${icon('log-out')} 退出登录</a><button class="btn primary" data-close>关闭</button>` });
    // 全局搜索
    const gs = $('#globalSearch'), sp = $('#searchPop');
    gs.oninput = () => {
      const q = gs.value.trim().toLowerCase(); if (!q) { sp.classList.remove('open'); return; }
      const A = DATA.assets.filter(inScope).filter(a => (a.name + a.ip + a.site).toLowerCase().includes(q)).slice(0, 5);
      const I = DATA.incidents.filter(inScope).filter(i => (i.name + i.assetName).toLowerCase().includes(q)).slice(0, 4);
      const T = DATA.tickets.filter(inScope).filter(t => (t.id + t.title).toLowerCase().includes(q)).slice(0, 3);
      const R = DATA.runbooks.filter(r => r.name.toLowerCase().includes(q)).slice(0, 3);
      let h = '';
      if (A.length) h += `<div class="sp-group">资产</div>` + A.map(a => `<a href="asset-detail.html?id=${a.id}">${typeIcon(a.type)}<span>${esc(a.name)}</span><small>${a.ip} · ${esc(a.site)}</small></a>`).join('');
      if (I.length) h += `<div class="sp-group">事件</div>` + I.map(i => `<a href="incident-detail.html?id=${i.id}">${sevBadge(i.severity)}<span class="ellipsis">${esc(i.name)}</span><small>${esc(i.assetName)}</small></a>`).join('');
      if (T.length) h += `<div class="sp-group">工单</div>` + T.map(t => `<a href="tickets.html?id=${t.id}">${icon('ticket')}<span>${esc(t.title)}</span><small>${t.id}</small></a>`).join('');
      if (R.length) h += `<div class="sp-group">Runbook</div>` + R.map(r => `<a href="automation.html?rb=${r.id}">${icon('zap')}<span>${esc(r.name)}</span><small>v${r.version}</small></a>`).join('');
      sp.innerHTML = h || `<div class="empty small">没有匹配「${esc(q)}」的对象（仅搜索当前租户范围）</div>`; sp.classList.add('open');
    };
    gs.onfocus = () => { if (gs.value.trim()) sp.classList.add('open'); };
    document.addEventListener('keydown', (e) => { if (e.key === '/' && !/input|textarea|select/i.test(document.activeElement.tagName)) { e.preventDefault(); gs.focus(); } if (e.key === 'Escape') { $$('.pop.open,.search-pop.open').forEach(p => p.classList.remove('open')); } });
    document.addEventListener('click', (e) => { if (!e.target.closest('.pop,.search,#tenantSwitch,#bellBtn')) $$('.pop.open,.search-pop.open').forEach(p => p.classList.remove('open')); });
    // 新鲜度
    if (freshTimer) clearInterval(freshTimer);
    let sec = 3; freshTimer = setInterval(() => { sec = sec >= 29 ? 2 : sec + 1; const el = $('#freshSec'); if (el) el.textContent = sec; }, 1000);
  }

  function deniedHtml(navItem) {
    const allowed = navItem.roles.map(r => ROLES[r].name).join('、');
    return `<div class="denied card"><div class="card-body" style="padding:36px 28px"><div class="lock">${icon('lock')}</div><h2>当前角色无权访问「${navItem.label}」</h2><p>权限优先的导航：组件在请求数据之前就按角色状态决定是否渲染，不会闪现未授权内容。<br>允许访问的角色：<b>${allowed}</b>。</p><div class="row" style="justify-content:center;gap:8px"><a class="btn" href="dashboard.html">返回总览</a><button class="btn primary" onclick="App.setRole('${navItem.roles[navItem.roles.length - 1]}')">切换为 ${ROLES[navItem.roles[navItem.roles.length - 1]].name}</button></div></div></div>`;
  }

  function renderContent() {
    const content = $('#content'); if (!content || !current) return;
    const nav = findNav(current.id);
    if (nav && !nav.item.roles.includes(state.role)) { content.innerHTML = deniedHtml(nav.item); return; }
    content.innerHTML = '';
    try { current.render(content, { tenant: tenant(), role: state.role, scope: tenantScope() });
      content.dataset.view=current.id;
      const desc=$('.page-head .sub',content); if(desc){const details=document.createElement('details');details.className='page-guide';const summary=document.createElement('summary');summary.textContent='页面说明';desc.replaceWith(details);details.append(summary,desc);}
    }
    catch (err) { console.error(err); content.innerHTML = `<div class="callout danger">${icon('alert-triangle')}<div><b>页面渲染出错</b><div class="mono small">${esc(err.message)}</div></div></div>`; }
  }
  function rerender() { if (!current) return; mountShell(current.id, current.title); renderContent(); }

  App.page = function (id, render, opts) {
    current = { id, render, title: opts && opts.title };
    const run = () => { mountShell(id, current.title); renderContent(); };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run); else run();
  };
  App.setTitle = function (title) { if (current) current.title = title; const b = $('.crumbs b'); if (b) b.textContent = title; document.title = title + ' · MSP 托管运维平台'; };
  App.setRole = function (r) { state.role = r; store.set('role', r); normalizeTenant(); store.set('tenant', state.tenant); rerender(); };
  App.setTenant = function (t) { state.tenant = t; normalizeTenant(); store.set('tenant', state.tenant); rerender(); };
  App.rerender = rerender;

  /* ---------------- 通用组件 ---------------- */
  function toast(msg, type = 'ok', ms = 3600) {
    const box = $('#toasts') || (() => { const d = document.createElement('div'); d.className = 'toasts'; d.id = 'toasts'; document.body.appendChild(d); return d; })();
    const t = document.createElement('div'); t.className = 'toast ' + type;
    t.innerHTML = icon({ ok: 'check-circle', warn: 'alert-triangle', danger: 'x-circle', info: 'info' }[type] || 'info') + `<span>${msg}</span>`;
    box.appendChild(t); setTimeout(() => { t.style.opacity = '0'; t.style.transition = 'opacity .25s'; setTimeout(() => t.remove(), 260); }, ms);
  }
  App.toast = toast;

  let layer = null;
  function closeLayer() { if (layer) { layer.close(); } }
  function makeLayer(kind, cfg) {
    closeLayer();
    const ov = document.createElement('div'); ov.className = 'overlay';
    const el = document.createElement('div'); el.className = kind + (cfg.size ? ' ' + cfg.size : '');
    if (cfg.width) el.style.width = cfg.width + 'px';
    const headCls = kind + '-head', bodyCls = kind + '-body', footCls = kind + '-foot';
    el.innerHTML = `<div class="${headCls}"><div><h3>${cfg.title || ''}</h3>${cfg.sub ? `<div class="sub">${cfg.sub}</div>` : ''}</div><button class="icon-btn" data-close title="关闭">${icon('x')}</button></div><div class="${bodyCls}">${cfg.body || ''}</div>${cfg.foot ? `<div class="${footCls}">${cfg.foot}</div>` : ''}`;
    document.body.append(ov, el);
    requestAnimationFrame(() => { ov.classList.add('show'); el.classList.add('show'); });
    let closed = false;
    const onKey = (e) => { if (e.key === 'Escape') api.close(); };
    const api = { el, close() { if (closed) return; closed = true; document.removeEventListener('keydown', onKey); ov.classList.remove('show'); el.classList.remove('show'); setTimeout(() => { ov.remove(); el.remove(); }, 220); if (layer === api) layer = null; cfg.onClose && cfg.onClose(); } };
    ov.onclick = () => { if (cfg.persistent) return; api.close(); };
    el.addEventListener('click', (e) => { if (e.target.closest('[data-close]')) api.close(); });
    document.addEventListener('keydown', onKey);
    layer = api; cfg.onOpen && cfg.onOpen(el, api); return api;
  }
  App.drawer = (cfg) => makeLayer('drawer', cfg);
  App.modal = (cfg) => makeLayer('modal', cfg);
  App.closeLayer = closeLayer;
  App.confirm = ({ title = '请确认', text = '', okText = '确认', cancelText = '取消', danger = false, body = '' }) => new Promise((resolve) => {
    let decided = false;
    const m = App.modal({ title, body: `<p style="font-size:13.5px;color:var(--text-2)">${text}</p>${body}`, foot: `<button class="btn" data-close>${cancelText}</button><button class="btn ${danger ? 'danger solid' : 'primary'}" id="__ok">${okText}</button>`, onClose: () => { if (!decided) { decided = true; resolve(false); } } });
    $('#__ok', m.el).onclick = () => { decided = true; resolve(true); m.close(); };
  });
  const modal = App.modal;

  App.tabs = function (root, group, onChange) {
    root = typeof root === 'string' ? $(root) : root;
    if (typeof group === 'function') { onChange = group; group = null; }
    const sel = group ? `.tabs[data-group="${group}"] [data-tab]` : '.tabs [data-tab]';
    const psel = group ? `.tab-panel[data-group="${group}"]` : '.tab-panel';
    const btns = $$(sel, root), panels = $$(psel, root);
    function activate(id) { btns.forEach(b => b.classList.toggle('active', b.dataset.tab === id)); panels.forEach(p => p.classList.toggle('active', p.dataset.panel === id)); onChange && onChange(id); window.dispatchEvent(new Event('resize')); }
    btns.forEach(b => b.addEventListener('click', () => activate(b.dataset.tab)));
    const init = btns.find(b => b.classList.contains('active')) || btns[0]; if (init) activate(init.dataset.tab);
    return { activate };
  };

  App.seg = function (root, onChange) {
    root = typeof root === 'string' ? $(root) : root;
    $$('button', root).forEach(b => b.addEventListener('click', () => { $$('button', root).forEach(x => x.classList.remove('active')); b.classList.add('active'); onChange && onChange(b.dataset.v, b); }));
  };

  App.chips = function (root, onChange, multi) {
    root = typeof root === 'string' ? $(root) : root;
    root.addEventListener('click', (e) => {
      const c = e.target.closest('.chip'); if (!c) return;
      if (multi) c.classList.toggle('active'); else { $$('.chip', root).forEach(x => x.classList.remove('active')); c.classList.add('active'); }
      onChange && onChange(multi ? $$('.chip.active', root).map(x => x.dataset.v) : c.dataset.v);
    });
  };

  /* 数据表格 */
  App.table = function (container, opt) {
    const el = typeof container === 'string' ? $(container) : container;
    const st = { sortKey: opt.sortKey || null, sortDir: opt.sortDir || 'asc', page: 1, pageSize: opt.pageSize || 15, selected: new Set(), rows: opt.rows || [] };
    const rowKey = opt.rowKey || 'id';
    function sorted() {
      const rows = st.rows.slice();
      if (st.sortKey) {
        const col = opt.columns.find(c => c.key === st.sortKey); if (!col) return rows;
        const get = col.sortVal || ((r) => r[col.key]);
        rows.sort((a, b) => { const va = get(a), vb = get(b); const cmp = (typeof va === 'number' && typeof vb === 'number') ? va - vb : String(va ?? '').localeCompare(String(vb ?? ''), 'zh'); return st.sortDir === 'asc' ? cmp : -cmp; });
      }
      return rows;
    }
    function render() {
      const rows = sorted(); const total = rows.length; const pages = Math.max(1, Math.ceil(total / st.pageSize)); if (st.page > pages) st.page = pages;
      const start = (st.page - 1) * st.pageSize; const pageRows = opt.paginate === false ? rows : rows.slice(start, start + st.pageSize);
      const colCount = opt.columns.length + (opt.selectable ? 1 : 0);
      let h = '';
      if (opt.selectable && st.selected.size) h += `<div class="bulkbar">已选择 <b>${st.selected.size}</b> 项 <span class="spacer" style="flex:1"></span>${opt.bulkActions || ''} <button class="btn ghost xs" data-act="clear">取消选择</button></div>`;
      h += `<div class="table-wrap ${opt.scroll ? 'scroll' : ''}" ${opt.maxHeight ? `style="max-height:${opt.maxHeight}px"` : ''}><table class="table ${opt.dense ? 'dense' : ''}"><thead><tr>`;
      if (opt.selectable) h += `<th class="check-col"><input type="checkbox" data-act="selall" ${pageRows.length && pageRows.every(r => st.selected.has(r[rowKey])) ? 'checked' : ''}></th>`;
      for (const c of opt.columns) {
        const sortable = c.sortable !== false && c.key;
        h += `<th class="${sortable ? 'sortable' : ''} ${c.num ? 'num' : ''} ${st.sortKey === c.key ? 'sorted' : ''}" ${c.width ? `style="width:${c.width}"` : ''} data-key="${c.key || ''}">${c.label}${sortable ? `<span class="sort">${st.sortKey === c.key ? (st.sortDir === 'asc' ? '▲' : '▼') : '⇅'}</span>` : ''}</th>`;
      }
      h += '</tr></thead><tbody>';
      if (!pageRows.length) h += `<tr><td colspan="${colCount}"><div class="empty">${emptyIllustration()}<div>${opt.empty || '当前筛选条件下没有数据'}</div><small>试试调整筛选条件，或稍后刷新查看</small></div></td></tr>`;
      for (const r of pageRows) {
        const id = r[rowKey];
        h += `<tr data-id="${esc(id)}" class="${opt.onRow ? 'clickable' : ''} ${st.selected.has(id) ? 'selected' : ''} ${opt.rowClass ? opt.rowClass(r) : ''}">`;
        if (opt.selectable) h += `<td class="check-col"><input type="checkbox" data-act="sel" ${st.selected.has(id) ? 'checked' : ''}></td>`;
        for (const c of opt.columns) h += `<td class="${c.num ? 'num' : ''} ${c.cls || ''}">${c.render ? c.render(r) : esc(r[c.key] ?? '')}</td>`;
        h += '</tr>';
      }
      h += '</tbody></table></div>';
      if (opt.paginate !== false) {
        let pg = ''; const win = 5; let s = Math.max(1, st.page - 2), e = Math.min(pages, s + win - 1); s = Math.max(1, e - win + 1);
        for (let i = s; i <= e; i++) pg += `<button data-page="${i}" class="${i === st.page ? 'active' : ''}">${i}</button>`;
        h += `<div class="pager"><span>共 <b>${fmt.n(total)}</b> 条 · 第 ${st.page}/${pages} 页${opt.footNote ? ' · ' + opt.footNote : ''}</span><div class="pages"><button data-page="${st.page - 1}" ${st.page === 1 ? 'disabled' : ''}>‹</button>${pg}<button data-page="${st.page + 1}" ${st.page === pages ? 'disabled' : ''}>›</button><select class="select sm" data-act="size" style="width:auto;height:26px;margin-left:6px">${[10, 15, 25, 50, 100].map(n => `<option ${n === st.pageSize ? 'selected' : ''}>${n}</option>`).join('')}</select></div></div>`;
      } else if (opt.paginate !== false && opt.footNote) h += `<div class="pager"><span>共 <b>${fmt.n(total)}</b> 条 · ${opt.footNote}</span></div>`;
      el.innerHTML = h;
    }
    el.addEventListener('click', (e) => {
      const th = e.target.closest('th.sortable'); if (th) { const k = th.dataset.key; if (st.sortKey === k) st.sortDir = st.sortDir === 'asc' ? 'desc' : 'asc'; else { st.sortKey = k; st.sortDir = 'asc'; } render(); return; }
      const pb = e.target.closest('[data-page]'); if (pb) { st.page = clamp(Number(pb.dataset.page), 1, 9999); render(); return; }
      const act = e.target.closest('[data-act]');
      if (act) {
        const a = act.dataset.act;
        if (a === 'clear') { st.selected.clear(); render(); opt.onSelect && opt.onSelect([...st.selected]); return; }
        if (a === 'selall') { const rows = sorted(); const start = (st.page - 1) * st.pageSize; const pr = opt.paginate === false ? rows : rows.slice(start, start + st.pageSize); const all = pr.every(r => st.selected.has(r[rowKey])); pr.forEach(r => all ? st.selected.delete(r[rowKey]) : st.selected.add(r[rowKey])); render(); opt.onSelect && opt.onSelect([...st.selected]); return; }
        if (a === 'sel') { const id = act.closest('tr').dataset.id; const row = st.rows.find(r => String(r[rowKey]) === id); if (row) { st.selected.has(row[rowKey]) ? st.selected.delete(row[rowKey]) : st.selected.add(row[rowKey]); } render(); opt.onSelect && opt.onSelect([...st.selected]); return; }
        if (a === 'size') return;
      }
      if (e.target.closest('button,a,input,select,label,[data-stop]')) return;
      const tr = e.target.closest('tr[data-id]'); if (tr && opt.onRow) { const row = st.rows.find(r => String(r[rowKey]) === tr.dataset.id); if (row) opt.onRow(row, tr); }
    });
    el.addEventListener('change', (e) => { const s = e.target.closest('[data-act="size"]'); if (s) { st.pageSize = Number(s.value); st.page = 1; render(); } });
    render();
    return { update(rows) { st.rows = rows; st.page = 1; render(); }, refresh: render, state: st, selected: () => [...st.selected], clearSelection() { st.selected.clear(); render(); } };
  };

  App.kpi = function ({ label, value, unit, sub, cls, spark, sparkColor, delta, deltaDir, deltaGood, help }) {
    const id = 'sp' + uid();
    const d = delta != null ? `<span class="delta ${deltaGood === true ? 'good' : deltaGood === false ? 'bad' : 'flat'}">${deltaDir === 'up' ? '↑' : deltaDir === 'down' ? '↓' : '→'} ${delta}</span>` : '';
    setTimeout(() => { const el = document.getElementById(id); if (el && spark && global.Charts) Charts.spark(el, spark, { color: sparkColor || 'var(--primary)', area: true }); }, 0);
    return `<div class="kpi ${cls || ''}"><div class="k-label"><span>${label}</span>${help ? `<span class="faint" title="${esc(help)}">${icon('info')}</span>` : ''}</div><div class="k-value">${value}${unit ? `<small>${unit}</small>` : ''}</div><div class="k-sub">${d}${sub || ''}</div>${spark ? `<div class="k-spark" id="${id}"></div>` : ''}</div>`;
  };

  App.copy = function (text) { try { navigator.clipboard.writeText(text); toast('已复制到剪贴板', 'info', 1500); } catch (e) { toast('复制失败', 'danger'); } };

  global.App = App;
})(window);
