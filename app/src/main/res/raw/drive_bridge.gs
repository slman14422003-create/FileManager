var SECRET = 'CHANGE_ME_TO_A_LONG_SECRET';

function setup() {
  DriveApp.getRootFolder();
  Logger.log(check_() ? 'OK' : 'SECRET too short or unchanged');
}

function check_() {
  return typeof SECRET === 'string' && SECRET.length >= 12 && SECRET.indexOf('CHANGE_ME') !== 0;
}

function same_(a, b) {
  if (a.length !== b.length) return false;
  var d = 0;
  for (var i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

function out_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function doPost(e) {
  var k = e && e.parameter && e.parameter.k ? String(e.parameter.k) : '';
  if (!check_() || !same_(k, SECRET)) return out_({ ok: false });
  return out_({ ok: true, token: ScriptApp.getOAuthToken() });
}

function doGet() {
  return out_({ ok: false });
}
