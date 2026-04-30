require('dotenv').config();

const express = require('express');
const cors = require('cors');
const { RtcTokenBuilder, RtcRole } = require('agora-access-token');

const app = express();
app.use(cors());

const APP_ID = process.env.AGORA_APP_ID || '';
const APP_CERTIFICATE = process.env.AGORA_APP_CERTIFICATE || '';
const PORT = parseInt(process.env.PORT || '3000', 10);
const TOKEN_EXPIRE_SECONDS = parseInt(process.env.TOKEN_EXPIRE_SECONDS || '3600', 10);

function buildToken(channelName, roleName, uid) {
  if (!APP_ID || !APP_CERTIFICATE) {
    throw new Error('AGORA_APP_ID atau AGORA_APP_CERTIFICATE belum diisi');
  }

  const expireTs = Math.floor(Date.now() / 1000) + TOKEN_EXPIRE_SECONDS;
  const role = roleName === 'publisher' ? RtcRole.PUBLISHER : RtcRole.SUBSCRIBER;
  const userId = Number.isNaN(Number(uid)) ? 0 : Number(uid);

  return RtcTokenBuilder.buildTokenWithUid(
    APP_ID,
    APP_CERTIFICATE,
    channelName,
    userId,
    role,
    expireTs
  );
}

app.get('/rtc/:channel/:role/:uid', (req, res) => {
  try {
    const { channel, role, uid } = req.params;
    const token = buildToken(channel, role, uid);
    res.json({
      appId: APP_ID,
      channel,
      role,
      uid,
      token,
      expiresIn: TOKEN_EXPIRE_SECONDS
    });
  } catch (err) {
    res.status(500).json({
      error: err.message || 'Unknown error'
    });
  }
});

app.get('/', (_req, res) => {
  res.send('Agora token server is running');
});

app.listen(PORT, () => {
  console.log(`Token server running on http://0.0.0.0:${PORT}`);
});
