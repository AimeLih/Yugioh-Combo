# Yu-Gi-Oh! Combo Search

A full-stack card search and combo simulator for exploring legal Yu-Gi-Oh! card interactions. Search the card database, inspect card details, follow suggested combo branches, pay material costs, and track cards as they move between simulated zones.

> This is an unofficial fan project. Yu-Gi-Oh! is a trademark of its respective owners. Card data and images are provided by the [YGOPRODeck API](https://ygoprodeck.com/api-guide/).

## Live App

Access the deployed project at [**yugioh-combo.vercel.app**](https://yugioh-combo.vercel.app/).

> **Cold-start notice:** If the backend has been inactive, the first request may take longer while the service starts. Refresh or retry the search after it becomes available.

You can also view the repository's latest development history on the [GitHub Activity page](https://github.com/AimeLih/Yugioh-Combo/activity).

## Features

- Search cards by partial name or exact name
- View card text, stats, type, archetype, artwork, and once-per-turn information
- Add selected search results to the Hand without resetting the current board
- Explore ranked combo starters, extenders, and enders
- See why a route is available, when it can be used, its destination, and its cost
- Manually move monsters, Spells, Traps, and Pendulum cards between supported zones
- Simulate the Hand, five Main Monster Zones, Extra Monster Zone, Spell & Trap Zones, Pendulum Zones, Graveyard, banished cards, and face-up Extra Deck
- Activate non-summon effects manually while prompting for on-summon effects immediately
- Treat applicable monsters as Continuous Spells or Traps while they occupy the back row
- Search for Synchro, Xyz, and Link Monsters and summon them only when legal materials are present
- Pendulum Summon multiple eligible monsters from the Hand and face-up Extra Deck using the current scale range and available legal zones
- Select legal Fusion Materials and other effect costs from searchable suggestions
- Preserve the board while inspecting more cards, or reset it intentionally with **Clear Zones**
- Advance turns and phases to unlock delayed effects
- Step backward through a combo while restoring the previous game state
- Enforce a conservative subset of Official Rulebook Version 10

## Tech Stack

- **Frontend:** React 19, Vite 8
- **Backend:** Java 17, Spring Boot 4, Spring Data JPA
- **Database:** PostgreSQL
- **Card data:** YGOPRODeck API
- **Testing:** JUnit 5, Mockito

## Project Structure

```text
.
├── frontend/                  # React/Vite client
├── src/main/java/             # Spring Boot API and combo engine
├── src/main/resources/        # Application configuration
├── src/test/                  # Backend tests
├── .env.example               # Local environment template
└── Dockerfile                 # Production backend image
```

## Getting Started

### Prerequisites

- Java 17
- Node.js and npm
- PostgreSQL

### 1. Configure the database

Create a PostgreSQL database, then copy the example environment file:

```bash
cp .env.example .env
```

On PowerShell:

```powershell
Copy-Item .env.example .env
```

Set the connection values in `.env`:

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/yugioh
SPRING_DATASOURCE_USERNAME=your_database_user
SPRING_DATASOURCE_PASSWORD=your_database_password
SPRING_CACHE_TYPE=simple
IMPORT_TOKEN=choose_a_private_local_token
```

### 2. Start the backend

macOS/Linux:

```bash
./mvnw spring-boot:run
```

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

The API starts at `http://localhost:8080`.

### 3. Import card data

With the backend running against an empty database, import the current card catalog:

```bash
curl -X POST -H "X-Import-Token: choose_a_private_local_token" http://localhost:8080/yugioh/admin/import
```

The header value must match `IMPORT_TOKEN`. The import can take a moment because it fetches and stores card details and images from YGOPRODeck.

### 4. Start the frontend

In a second terminal:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`.

The frontend uses `http://localhost:8080` by default. To point it at another backend, create `frontend/.env.local`:

```dotenv
VITE_API_URL=https://your-api.example.com
```

## Using the App

1. Search for a card by name.
2. Select a result to inspect it and add a copy to your Hand. Existing zones are preserved.
3. Use the card's zone actions to Summon it, Set it, or place a Pendulum Monster in a Pendulum Zone.
4. On-summon effects prompt immediately. Activate other effects manually from the card's current zone.
5. Choose an available starter, extender, follow-up, or ender and pay any requested material costs.
6. Use **Synchro / Xyz / Link** to search for a legal Extra Deck summon, or **Pendulum Summon** after establishing two scales.
7. Advance the phase for timing-locked effects, use **Back** to revisit a route, or use **Clear Zones** to start with an empty board.

The combo engine presents routes it can derive from card text and the modeled game state. Extra Deck material validation covers common Synchro, Xyz, and Link requirements; complex card-specific rulings may still require manual judgment.

## API

All endpoints use the `/yugioh` prefix.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/admin/import` | Import cards not already in the database |
| `GET` | `/card?name=...` | Find one card by exact name |
| `GET` | `/card/substring?name=...` | Search up to 50 cards by partial name |
| `GET` | `/card/combos?name=...&zone=...` | Find supported combo routes |
| `GET` | `/card/fusion-materials?source=...&target=...` | Plan legal Fusion Materials |
| `GET` | `/card/cost-materials?source=...&target=...` | Plan legal effect costs |

Example:

```bash
curl "http://localhost:8080/yugioh/card/combos?name=Branded%20Fusion"
```

## Tests and Builds

Run the backend tests:

```bash
./mvnw test
```

On Windows, use `.\mvnw.cmd test`.

Check and build the frontend:

```bash
cd frontend
npm run lint
npm run build
```

Build the backend container:

```bash
docker build -t yugioh-combo-search .
```

## Current Scope

The simulator validates general rulebook behavior and parsed card text; it is not a tournament judge. Individual rulings, errata, Forbidden/Limited lists, and tournament policy are outside the current scope.
