# Library Management System

A web-based library desk built with plain Java and JDBC. The Java program is both the web server and the database layer, so there is no framework to install.

## What it does

- Staff sign-in (passwords are stored as SHA-256 hashes, sessions use an HttpOnly cookie)
- Book catalogue shown as covers, grouped by category, with search
- Add, edit and delete books and members
- Issue and return books with automatic rules:
  - 14-day loans
  - 3 books per member at a time
  - no second copy of the same title for one member
  - fine of Rs. 2 per day for late returns
- Desk page with overdue books, running fines and recent activity
- Every database change that touches more than one table runs as a JDBC transaction (commit or rollback)

## Tech

| Part | Choice |
| --- | --- |
| Backend | Java 17+, `com.sun.net.httpserver` |
| Database | SQLite through JDBC (`sqlite-jdbc`), file `library.db` is created on first run |
| Frontend | HTML, CSS and vanilla JavaScript |

## Run it

1. Install a JDK (17 or newer) and check with `javac -version`.
2. Windows: double-click `run.bat`. Mac or Linux: `sh run.sh`.
3. Open http://localhost:8080
4. Sign in with `admin` / `library123`

To start with fresh sample data, stop the server and delete `library.db`.

## Project layout

```
src/lms/Main.java   HTTP server, routes, business rules
src/lms/Db.java     JDBC helpers, schema, sample data
src/lms/Json.java   small JSON writer
web/                index.html, styles.css, app.js
lib/                sqlite-jdbc driver
```

## Using MySQL instead

Everything database-related is in `Db.java`. To switch, add the MySQL connector jar to `lib/`, change `URL` to `jdbc:mysql://localhost:3306/library` (with user and password), and replace `AUTOINCREMENT` with `AUTO_INCREMENT` in the table definitions.
