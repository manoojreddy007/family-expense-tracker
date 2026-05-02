from flask import Flask, render_template, request, redirect
from flask_sqlalchemy import SQLAlchemy

from flask_login import (
    LoginManager,
    UserMixin,
    login_user,
    logout_user,
    login_required,
    current_user
)

from werkzeug.security import (
    generate_password_hash,
    check_password_hash
)


app = Flask(__name__)

# Database Configuration
app.config['SQLALCHEMY_DATABASE_URI'] = 'sqlite:///expense_tracker.db'
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

db = SQLAlchemy(app)

# Secret key for sessions
app.config['SECRET_KEY'] = 'mysecretkey'

# Flask Login Manager
login_manager = LoginManager()
login_manager.init_app(app)
login_manager.login_view = "login"

@login_manager.user_loader
def load_user(user_id):
    return User.query.get(int(user_id))


# -----------------------------
# User Table
# -----------------------------
class User(UserMixin, db.Model):
    id = db.Column(db.Integer, primary_key=True)

    name = db.Column(db.String(100), nullable=False)

    email = db.Column(db.String(100), unique=True, nullable=False)

    password = db.Column(db.String(200), nullable=False)


# -----------------------------
# Transaction Table
# -----------------------------
class Transaction(db.Model):
    id = db.Column(db.Integer, primary_key=True)

    amount = db.Column(db.Float, nullable=False)

    category = db.Column(db.String(100), nullable=False)

    type = db.Column(db.String(50), nullable=False)

    notes = db.Column(db.String(300))

    date = db.Column(db.String(50))

    added_by = db.Column(db.String(100))


# -----------------------------
# Home Page
# -----------------------------
@app.route("/")
@login_required
def home():
    transactions = Transaction.query.all()
    total_income = 0
    total_expense = 0
    for transaction in transactions:
        if transaction.type == "Income":
            total_income += transaction.amount
        elif transaction.type == "Expense":
            total_expense += transaction.amount
    savings = total_income - total_expense
    return render_template(
        "index.html",
        transactions=transactions,
        total_income=total_income,
        total_expense=total_expense,
        savings=savings
    )


# -----------------------------
# Register
# -----------------------------
@app.route("/register", methods=["GET", "POST"])
def register():
    if request.method == "POST":
        name = request.form["name"]
        email = request.form["email"]
        password = request.form["password"]
        hashed_password = generate_password_hash(password)
        new_user = User(
            name=name,
            email=email,
            password=hashed_password
        )
        db.session.add(new_user)
        db.session.commit()
        return redirect("/login")
    return render_template("register.html")


# -----------------------------
# Login
# -----------------------------
@app.route("/login", methods=["GET", "POST"])
def login():
    error = None
    if request.method == "POST":
        email = request.form["email"]
        password = request.form["password"]
        user = User.query.filter_by(email=email).first()
        if user and check_password_hash(user.password, password):
            login_user(user)
            return redirect("/")
        else:
            error = "Invalid email or password."
    return render_template("login.html", error=error)


# -----------------------------
# Logout
# -----------------------------
@app.route("/logout")
@login_required
def logout():
    logout_user()
    return redirect("/login")


# -----------------------------
# Add Expense
# -----------------------------
@app.route("/add", methods=["GET", "POST"])
@login_required
def add_expense():

    if request.method == "POST":

        amount = request.form["amount"]

        category = request.form["category"]

        transaction_type = request.form["type"]

        notes = request.form["notes"]

        date = request.form["date"]

        added_by = request.form["added_by"]

        new_transaction = Transaction(
            amount=amount,
            category=category,
            type=transaction_type,
            notes=notes,
            date=date,
            added_by=added_by
        )

        db.session.add(new_transaction)

        db.session.commit()

        return redirect("/")

    return render_template("add_expense.html")

# -----------------------------
# Delete Transaction
# -----------------------------
@app.route("/delete/<int:id>")
@login_required
def delete_transaction(id):

    transaction = Transaction.query.get(id)

    if transaction:
        db.session.delete(transaction)
        db.session.commit()

    return redirect("/")


# Create DB tables
with app.app_context():
    db.create_all()


if __name__ == "__main__":
    app.run(debug=True)