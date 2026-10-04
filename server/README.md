# Local receiver

Run the receiver on the same LAN as the phone. It accepts POST `/api/device-sync/v1`, GET health, and authenticated binary file uploads at `/api/device-sync/v1/files`.

Example:

```bash
DEVICE_SYNC_TOKEN="replace-with-a-long-random-token" npm start
```

Then enter `http://<LAN-IP>:3000/api/device-sync/v1` and the same token in the Android app.

For a real deployment, use the Hirmand site's authenticated API or another HTTPS endpoint. The local receiver is intended for private-network development and testing.
