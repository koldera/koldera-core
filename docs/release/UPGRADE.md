# Upgrading koldera-core

Read the version's entry in [CHANGELOG.md](CHANGELOG.md) first; it lists changes to settings and to the API.

1. Check, unpack and load the new bundle as in [INSTALL.md](INSTALL.md), steps 1 and 2. Keep the old image: loading a
   new version does not remove it.
2. Back up the data volume (uploads, jobs, artifacts and the persisted signing key), for example with
   `docker compose -f source/docker/compose.yaml stop core` and a copy of the volume.
3. Point `KOLDERA_IMAGE` at the new tag and recreate the containers with the new bundle's compose file:

   ```bash
   export KOLDERA_IMAGE=koldera-core:X.Y.Z
   docker compose -f source/docker/compose.yaml up -d --build --wait
   curl -H "Authorization: Bearer $KOLDERA_TOKEN" http://localhost:8080/v1/info   # reports the new version
   ```

Jobs that were running during the restart are not resumed; submit them again.

## Rolling back

Set `KOLDERA_IMAGE` back to the previous tag, restore the data volume from step 2 if the new version has written to
it, and run the previous bundle's `docker compose ... up -d --wait`.
