const test = require('node:test');
const assert = require('node:assert/strict');
const K = require('../assets/js/knowledge-model.js');
const viewer = { role: 'viewer', tenant: 't-huadong', user: '王芸' };
const admin = { role: 'padmin', tenant: 't-huadong', user: '刘岚' };
const base = [
  { id: 'shared', tenant: null, status: 'published', title: 'Proxy 离线排查', author: '刘岚', category: '监控接入', type: 'normal', tags: ['Proxy'], content: 'Proxy 离线时检查网络和证书。\n检查本地缓冲是否积压。', likes: 2, views: 5 },
  { id: 'hd', tenant: 't-huadong', status: 'published', title: '华东网络故障', author: '陈立', category: '网络运维', type: 'normal', tags: ['网络故障'], content: '网络故障先核查核心交换机端口。', likes: 0, views: 0 },
  { id: 'xc', tenant: 't-xingchen', status: 'published', title: '星辰网络故障', author: '周敏', category: '网络运维', type: 'normal', tags: ['网络故障'], content: '其他客户的网络资料。', likes: 0, views: 0 },
  { id: 'draft', tenant: 't-huadong', status: 'draft', title: '未公开草稿', author: '陈立', category: '网络运维', type: 'normal', tags: [], content: '草稿证据。', likes: 0, views: 0 }
];
test('customer scope permits shared and own published articles, hides other customers and drafts', () => {
  assert.deepEqual(K.visible(base, viewer).map(a => a.id), ['shared', 'hd']);
  assert.equal(K.visible(base, { ...admin, tenant: 'all' }).length, 3);
  assert.equal(K.visible(base, { ...viewer, user: '陈立' }).length, 3);
});
test('retrieval uses visible evidence and returns nothing for unsupported questions', () => {
  const hits = K.retrieve(base, viewer, '网络故障怎么排查');
  assert.equal(hits[0].article.id, 'hd');
  assert.ok(hits.every(h => h.article.id !== 'xc'));
  assert.equal(K.retrieve(base, viewer, '月球采矿成本是多少').length, 0);
  assert.ok(K.retrieve(base, viewer, 'Proxy离线怎么处理')[0].excerpt.includes('检查网络'));
});
test('only author in a writable role can edit an article; scope is enforced', () => {
  assert.equal(K.canEdit(base[0], viewer), false);
  assert.equal(K.canEdit(base[0], admin), true);
  assert.equal(K.canEdit(base[1], admin), false);
  assert.equal(K.canEdit(base[2], { ...admin, user: '周敏' }), false);
});
test('troubleshooting questions prioritize procedure evidence over symptoms', () => {
  const article = { ...base[0], sections: [{ heading: '问题现象', text: 'Proxy 离线，采集延迟。' }, { heading: '排查步骤', text: '检查 Proxy 网络、进程和证书。' }] };
  assert.equal(K.retrieve([article], viewer, 'Proxy离线如何排查')[0].heading, '排查步骤');
});
test('required publication fields and foreign tenant writes are rejected', () => {
  const form = { title: '处理步骤', content: '检查网络', category: '网络运维', type: 'normal', tags: ['网络'], tenant: 't-huadong' };
  assert.doesNotThrow(() => K.validateArticle(form, admin));
  assert.throws(() => K.validateArticle({ ...form, title: '   ' }, admin), /标题/);
  assert.throws(() => K.validateArticle({ ...form, content: '' }, admin), /正文/);
  assert.throws(() => K.validateArticle({ ...form, tenant: 't-xingchen' }, admin), /客户/);
  assert.throws(() => K.validateArticle(form, viewer), /只读/);
});
