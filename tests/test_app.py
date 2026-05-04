import os
from pathlib import Path

db_path = Path(__file__).resolve().parents[1] / "instance" / "test_app.db"
db_path.parent.mkdir(exist_ok=True)
os.environ["DATABASE_URL"] = f"sqlite:///{db_path.as_posix()}"
os.environ["SECRET_KEY"] = "test-secret"

from app import Transaction, app, db


def login(client):
    with client.session_transaction() as flask_session:
        flask_session["authenticated"] = True


def reset_database():
    with app.app_context():
        db.drop_all()
        db.create_all()


def add_transaction(**overrides):
    values = {
        "name": "Test transaction",
        "amount": 10,
        "category": "Food",
        "type": "Expense",
        "notes": "",
        "date": "2026-05-04",
        "added_by": "Manoj",
    }
    values.update(overrides)
    transaction = Transaction(**values)
    db.session.add(transaction)
    return transaction


def test_home_clamps_invalid_page_to_first_page():
    app.config["TESTING"] = True
    reset_database()

    with app.app_context():
        add_transaction(name="Visible transaction")
        db.session.commit()

    with app.test_client() as client:
        login(client)
        response = client.get("/?page=0")

    assert response.status_code == 200
    assert b"Visible transaction" in response.data


def test_pdf_report_endpoint_includes_all_filtered_rows():
    app.config["TESTING"] = True
    reset_database()

    with app.app_context():
        for index in range(12):
            add_transaction(name=f"May transaction {index}", amount=index + 1)
        add_transaction(name="April transaction", date="2026-04-30")
        db.session.commit()

    with app.test_client() as client:
        login(client)
        response = client.get("/report.pdf?month=2026-05")

    assert response.status_code == 200
    assert response.content_type == "application/pdf"
    assert response.data.startswith(b"%PDF-1.4")
    assert b"Category-wise Expenses" in response.data
    assert b"Food: Rs." in response.data
    assert b"May transaction 0" in response.data
    assert b"May transaction 11" in response.data
    assert b"April transaction" not in response.data


def test_home_renders_pie_chart_without_chartjs_dependency():
    app.config["TESTING"] = True
    reset_database()

    with app.app_context():
        add_transaction(name="Food expense", category="Food", amount=30)
        add_transaction(name="Fuel expense", category="Fuel", amount=20)
        add_transaction(name="Income item", category="Salary", type="Income", amount=100)
        db.session.commit()

    with app.test_client() as client:
        login(client)
        response = client.get("/?month=2026-05")

    page = response.get_data(as_text=True)
    assert response.status_code == 200
    assert "css-pie" in page
    assert "conic-gradient(" in page
    assert "Food" in page
    assert "Fuel" in page
    assert "chart.js" not in page
    assert "pieChart" not in page
