# Commercial licence

> **Draft — pending legal review. Not legally approved.** This page explains the intended licensing model. The
> binding terms are those of the Koldera Commercial License Agreement you sign with the licensor. If this page and
> your signed agreement differ, the agreement applies.

koldera-core is **proprietary, commercial, source-available software**. The licensor is Roman Lakusta (the copyright
holder). Using it requires a signed Koldera Commercial License Agreement; see [LICENSE](LICENSE).

## What you get

- The source code of the licensed version, and the build files to build it yourself.
- The container image of that version, with the licence texts of the third-party components it ships.
- 12 months of maintenance from the start of the licence: new versions released in that period, and support.

## What you may do

- Read, modify and build the source for your own internal business purposes, within the scope of your agreement.
- Deploy it on infrastructure you own or control (your servers, your cloud account, your network).
- Call it over its HTTP API, and use the SDKs and the command-line client with it, from your own services.
- Keep using every version you received under the licence for as long as you like (perpetual licence per version).

The exact scope (which legal entities, affiliates and contractors are covered, and how many deployments) is set in
your agreement. See [docs/commercial/LICENSE-SCOPE.md](docs/commercial/LICENSE-SCOPE.md).

## What needs a separate written agreement

- Redistributing, reselling or sublicensing koldera-core, modified or not.
- Publishing its source code, or a modified version of it.
- White-label redistribution, or embedding it in a product you ship to others (OEM). See
  [docs/commercial/OEM.md](docs/commercial/OEM.md).
- Transferring the licence to an unrelated entity.

## When maintenance ends

When the 12-month maintenance period ends and is not renewed:

- The versions you already received keep working and stay licensed to you.
- You may keep maintaining your own permitted modified copy for your internal use.
- You no longer receive new versions or support until you renew.

See [docs/commercial/MAINTENANCE.md](docs/commercial/MAINTENANCE.md).

## No activation, no telemetry

koldera-core contains no licence keys, no activation, no runtime licence checks, no telemetry and no remote
shutdown. No feature is disabled when maintenance ends. It does not contact the licensor. Delivery is described in
[docs/commercial/DELIVERY.md](docs/commercial/DELIVERY.md).

## SDKs and command-line client

The SDKs and the command-line client (`koldera-cli`, `koldera-js`, `koldera-python`, `koldera-dotnet`, `koldera-go`)
are separate projects under the Apache-2.0 licence. Their licence does not grant any right to koldera-core.

## Third-party components

Third-party components keep their own licences, listed in [NOTICE](NOTICE). Those licences give you the rights they
give you for those components, whatever your agreement with the licensor says.

## How to buy

Write to licensing@koldera-accessibility.dev with your company name, the entities that will use koldera-core, how
you plan to deploy it, and whether you need OEM or redistribution rights. This page is not legal advice.

Homepage: https://koldera-accessibility.dev
