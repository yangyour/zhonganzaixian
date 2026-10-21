import os,subprocess,tempfile,time,urllib.request,json,pathlib,signal
root=pathlib.Path('/tmp/za-integration-work');env=os.environ.copy();env['PATH']='/tmp/za-toolchain/node_modules/.bin:'+env['PATH'];checks=[];p=None
log=tempfile.TemporaryFile()
try:
 p=subprocess.Popen(['npm','--prefix','apps/web','run','dev','--','--host','127.0.0.1','--port','28130','--strictPort'],cwd=root,env=env,stdout=log,stderr=log,start_new_session=True)
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 for i in range(80):
  if p.poll() is not None:raise RuntimeError('VITE_EXITED')
  try:
   with opener.open('http://127.0.0.1:28130/qa/cmdb-catalog.fixture.html',timeout=1) as r:assert r.status==200
   break
  except Exception:time.sleep(.25)
 else:raise RuntimeError('VITE_START_TIMEOUT')
 for path,marker in [('/sla-reports','/src/bootstrap.ts'),('/src/pages/SlaReportsPage.vue','exportServices'),('/src/utils/onCallBoard.ts','validateSlaService'),('/src/utils/slaServiceReviewExport.ts','CURRENT_LOADED_LIVE_SERVICES_ONLY')]:
  with opener.open('http://127.0.0.1:28130'+path,timeout=5) as r:body=r.read().decode();assert marker in body;checks.append({'path':path,'status':r.status,'expected_marker_present':True})
finally:
 if p and p.poll() is None:
  os.killpg(p.pid,signal.SIGTERM)
  try:p.wait(timeout=10)
  except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait()
 log.close()
result={'scope':'LOOPBACK_VITE_HTTP_SMOKE_NOT_BROWSER_OR_API_ACCEPTANCE','host':'127.0.0.1','port':28130,'checks':checks,'vite_stopped':p is not None and p.poll() is not None,'browser_js_executed':False,'api_or_database_executed':False}
pathlib.Path('/tmp/za-sla-export-http.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result))
