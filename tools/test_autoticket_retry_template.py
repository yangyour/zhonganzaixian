import json,unittest
from pathlib import Path
class AutoTicketRetryTemplateTest(unittest.TestCase):
 def test_no_implicit_activation_or_real_dependencies(self):
  d=json.loads((Path(__file__).resolve().parents[1]/'deploy/examples/autoticket-registration-retry.json').read_text())
  self.assertFalse(d['enabled']);self.assertEqual('REGISTRATION_ONLY',d['scope'])
  for k in ['database_connection_reference','tenant_id','actor_id','mode','batch_size','poll_millis']:self.assertIsNone(d[k])
  for k in ['workflow_activation','provider_sends','production_approved']:self.assertFalse(d[k])
