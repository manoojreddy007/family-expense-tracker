import os
import math
from datetime import datetime
from functools import wraps

from flask import Flask, render_template, request, redirect, session, url_for, flash, Response
from flask_sqlalchemy import SQLAlchemy

app = Flask(__name__)

# Database Configuration
app.config['SQLALCHEMY_DATABASE_URI'] = os.environ.get('DATABASE_URL', 'sqlite:///expense_tracker.db')
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
app.config['SECRET_KEY'] = os.environ.get('SECRET_KEY', os.urandom(32))

db = SQLAlchemy(app)

# Shared password for login
APP_PASSWORD = os.environ.get("APP_PASSWORD", "family_secret")

CATEGORIES = ['Food', 'Groceries', 'Rent', 'Fuel', 'Shopping', 'Travel', 'Medical', 'Salary', 'Misc']
TRANSACTION_TYPES = ['Expense', 'Income']
PEOPLE = ['Manoj', 'Lalitha']
CHART_COLORS = [
    "#007bff",
    "#dc3545",
    "#28a745",
    "#ffc107",
    "#17a2b8",
    "#6f42c1",
    "#fd7e14",
    "#20c997",
    "#e83e8c",
]
MONTH_NAMES = {
    "01": "January",
    "02": "February",
    "03": "March",
    "04": "April",
    "05": "May",
    "06": "June",
    "07": "July",
    "08": "August",
    "09": "September",
    "10": "October",
    "11": "November",
    "12": "December",
}


# -----------------------------
# Transaction Table
# -----------------------------
class Transaction(db.Model):
    id = db.Column(db.Integer, primary_key=True)

    name = db.Column(db.String(100))

    amount = db.Column(db.Float, nullable=False)

    category = db.Column(db.String(100), nullable=False)

    type = db.Column(db.String(50), nullable=False)

    notes = db.Column(db.String(300))

    date = db.Column(db.String(50))

    added_by = db.Column(db.String(100))


# -----------------------------
# Simple Login
# -----------------------------
@app.route("/login", methods=["GET", "POST"])
def login():
    error = None
    if request.method == "POST":
        password = request.form["password"]
        if password == APP_PASSWORD:
            session["authenticated"] = True
            return redirect(url_for("home"))
        else:
            error = "Invalid password."
    return render_template("login.html", error=error)

@app.route("/logout")
def logout():
    session.pop("authenticated", None)
    return redirect(url_for("login"))

def login_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if not session.get("authenticated"):
            return redirect(url_for("login"))
        return f(*args, **kwargs)
    return decorated_function


def parse_transaction_form(form):
    name = form.get("name", "").strip()
    category = form.get("category", "").strip()
    transaction_type = form.get("type", "").strip()
    notes = form.get("notes", "").strip()
    date = form.get("date", "").strip()
    added_by = form.get("added_by", "").strip()

    try:
        amount = float(form.get("amount", ""))
    except ValueError:
        raise ValueError("Amount must be a valid number.")

    if not all([name, category, transaction_type, date, added_by]):
        raise ValueError("All fields except notes are required.")
    if amount <= 0:
        raise ValueError("Amount must be greater than zero.")
    if category not in CATEGORIES:
        raise ValueError("Please choose a valid category.")
    if transaction_type not in TRANSACTION_TYPES:
        raise ValueError("Please choose Income or Expense.")
    if added_by not in PEOPLE:
        raise ValueError("Please choose a valid person.")

    try:
        datetime.strptime(date, "%Y-%m-%d")
    except ValueError:
        raise ValueError("Please enter a valid date.")

    return {
        "name": name,
        "amount": amount,
        "category": category,
        "type": transaction_type,
        "notes": notes,
        "date": date,
        "added_by": added_by,
    }


def month_label(month):
    if not month:
        return "All Months"
    if len(month) == 7 and month[4] == "-":
        return f"{MONTH_NAMES.get(month[5:7], month[5:7])} {month[:4]}"
    return month


def transactions_for_month(month):
    query = Transaction.query
    if month:
        query = query.filter(Transaction.date.startswith(month))
    return query.order_by(Transaction.date.desc(), Transaction.id.desc()).all()


def build_category_chart(category_totals):
    total = sum(category_totals.values())
    if total <= 0:
        return [], ""

    segments = []
    start = 0
    categories_with_expenses = [
        (category, category_totals[category])
        for category in CATEGORIES
        if category_totals.get(category, 0) > 0
    ]

    for index, (category, amount) in enumerate(categories_with_expenses):
        percent = (amount / total) * 100
        end = 100 if index == len(categories_with_expenses) - 1 else start + percent
        color = CHART_COLORS[index % len(CHART_COLORS)]
        segments.append({
            "label": category,
            "amount": amount,
            "percent": percent,
            "color": color,
            "start": start,
            "end": end,
        })
        start = end

    chart_style = "conic-gradient(" + ", ".join(
        f"{segment['color']} {segment['start']:.2f}% {segment['end']:.2f}%"
        for segment in segments
    ) + ")"
    return segments, chart_style


def pdf_escape(value):
    text = str(value or "")
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def truncate(value, length):
    text = str(value or "")
    return text if len(text) <= length else f"{text[:length - 3]}..."


def text_command(x, y, text, size=10):
    return f"0 0 0 rg BT /F1 {size} Tf {x} {y} Td ({pdf_escape(text)}) Tj ET"


def pdf_rgb(hex_color):
    color = hex_color.lstrip("#")
    return tuple(int(color[index:index + 2], 16) / 255 for index in (0, 2, 4))


def color_command(hex_color):
    red, green, blue = pdf_rgb(hex_color)
    return f"{red:.3f} {green:.3f} {blue:.3f} rg"


def rect_command(x, y, width, height, color):
    return (
        "q\n"
        f"{color_command(color)}\n"
        f"{x:.2f} {y:.2f} {width:.2f} {height:.2f} re f\n"
        "Q"
    )


def pie_slice_command(cx, cy, radius, start_percent, end_percent, color):
    span = max(end_percent - start_percent, 0)
    steps = max(3, int(span / 3) + 1)
    points = []

    for step in range(steps + 1):
        percent = start_percent + (span * step / steps)
        angle = math.radians(90 - (percent * 3.6))
        points.append((
            cx + radius * math.cos(angle),
            cy + radius * math.sin(angle),
        ))

    path = [f"{cx:.2f} {cy:.2f} m"]
    path.extend(f"{x:.2f} {y:.2f} l" for x, y in points)
    path.append("h f")
    return "q\n" + color_command(color) + "\n" + "\n".join(path) + "\nQ"


def chart_commands(segments, cx=120, cy=610, radius=62):
    commands = [
        text_command(40, 700, "Category-wise Expenses", 12),
    ]

    for segment in segments:
        commands.append(
            pie_slice_command(
                cx,
                cy,
                radius,
                segment["start"],
                segment["end"],
                segment["color"],
            )
        )

    legend_x = 220
    legend_y = 662
    for index, segment in enumerate(segments):
        y = legend_y - (index * 18)
        commands.append(rect_command(legend_x, y - 3, 10, 10, segment["color"]))
        commands.append(text_command(
            legend_x + 16,
            y,
            f"{segment['label']}: Rs. {segment['amount']:.2f} ({segment['percent']:.1f}%)",
            8,
        ))

    return commands


def build_transactions_pdf(transactions, month, total_income, total_expense, savings):
    page_width = 595
    page_height = 842
    left = 40
    row_height = 18
    rows_per_page = 24

    header = [
        ("Name", 40, 22),
        ("Amount", 180, 16),
        ("Category", 255, 14),
        ("Type", 335, 10),
        ("Date", 395, 10),
        ("Added By", 470, 12),
    ]

    category_totals = {}
    for transaction in transactions:
        if transaction.type == "Expense":
            category_totals[transaction.category] = category_totals.get(transaction.category, 0) + transaction.amount
    chart_segments, _ = build_category_chart(category_totals)

    pages = []
    page_rows = [transactions[i:i + rows_per_page] for i in range(0, len(transactions), rows_per_page)] or [[]]

    for page_number, rows in enumerate(page_rows, start=1):
        y = page_height - 45
        commands = [
            text_command(left, y, "LA-MA NEST Expense Report", 16),
            text_command(left, y - 24, f"Month: {month_label(month)}", 11),
            text_command(left, y - 42, f"Total Income: Rs. {total_income:.2f}", 10),
            text_command(left, y - 58, f"Total Expense: Rs. {total_expense:.2f}", 10),
            text_command(left, y - 74, f"Savings: Rs. {savings:.2f}", 10),
            text_command(500, 30, f"Page {page_number}", 9),
        ]

        if page_number == 1 and chart_segments:
            commands.extend(chart_commands(chart_segments))
            table_y = 510
        else:
            table_y = y - 108

        for label, x, _ in header:
            commands.append(text_command(x, table_y, label, 9))

        table_y -= row_height
        if rows:
            for transaction in rows:
                values = [
                    truncate(transaction.name, 22),
                    f"Rs. {transaction.amount:.2f}",
                    truncate(transaction.category, 14),
                    transaction.type,
                    transaction.date,
                    truncate(transaction.added_by, 12),
                ]
                for value, (_, x, _) in zip(values, header):
                    commands.append(text_command(x, table_y, value, 8))
                table_y -= row_height
        else:
            commands.append(text_command(left, table_y, "No transactions found.", 10))

        pages.append("\n".join(commands))

    objects = []
    objects.append("<< /Type /Catalog /Pages 2 0 R >>")
    page_kids = " ".join(f"{3 + index * 2} 0 R" for index in range(len(pages)))
    objects.append(f"<< /Type /Pages /Kids [{page_kids}] /Count {len(pages)} >>")

    for index, content in enumerate(pages):
        page_obj_id = 3 + index * 2
        content_obj_id = page_obj_id + 1
        objects.append(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {page_width} {page_height}] "
            f"/Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> "
            f"/Contents {content_obj_id} 0 R >>"
        )
        content_bytes = content.encode("latin-1", "replace")
        objects.append(f"<< /Length {len(content_bytes)} >>\nstream\n{content}\nendstream")

    pdf = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for object_number, obj in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf.extend(f"{object_number} 0 obj\n{obj}\nendobj\n".encode("latin-1", "replace"))

    xref_offset = len(pdf)
    pdf.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode("ascii"))
    for offset in offsets[1:]:
        pdf.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    pdf.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
        f"startxref\n{xref_offset}\n%%EOF\n".encode("ascii")
    )
    return bytes(pdf)


# -----------------------------
# Home Page
# -----------------------------
@app.route("/", methods=["GET"])
@login_required
def home():
    current_month = datetime.now().strftime("%Y-%m")
    month = request.args.get("month", current_month)
    page = max(request.args.get("page", 1, type=int), 1)
    per_page = 10
    offset = (page - 1) * per_page

    query = Transaction.query
    if month:
        query = query.filter(Transaction.date.startswith(month))

    # totals use all filtered rows
    all_transactions = transactions_for_month(month)

    total_income = 0
    total_expense = 0
    category_totals = {}
    for transaction in all_transactions:
        if transaction.type == "Income":
            total_income += transaction.amount
        elif transaction.type == "Expense":
            total_expense += transaction.amount
            category_totals[transaction.category] = category_totals.get(transaction.category, 0) + transaction.amount

    savings = total_income - total_expense
    chart_segments, chart_style = build_category_chart(category_totals)

    # page rows
    page_transactions = (
        query.order_by(Transaction.date.desc(), Transaction.id.desc())
             .limit(per_page + 1)
             .offset(offset)
             .all()
    )
    has_next = len(page_transactions) > per_page
    if has_next:
        page_transactions = page_transactions[:-1]

    months = sorted(
        {t.date[:7] for t in Transaction.query.all() if t.date and len(t.date) >= 7},
        reverse=True
    )
    month_options = [
        (m, f"{MONTH_NAMES.get(m[5:7], m[5:7])} {m[:4]}")
        for m in months
    ]

    return render_template(
        "index.html",
        transactions=page_transactions,
        total_income=total_income,
        total_expense=total_expense,
        savings=savings,
        chart_segments=chart_segments,
        chart_style=chart_style,
        month_options=month_options,
        selected_month=month,
        page=page,
        has_next=has_next,
        categories=CATEGORIES,
        transaction_types=TRANSACTION_TYPES,
        people=PEOPLE,
    )


@app.route("/report.pdf", methods=["GET"])
@login_required
def download_report():
    month = request.args.get("month", "")
    transactions = transactions_for_month(month)

    total_income = 0
    total_expense = 0
    for transaction in transactions:
        if transaction.type == "Income":
            total_income += transaction.amount
        elif transaction.type == "Expense":
            total_expense += transaction.amount

    savings = total_income - total_expense
    pdf = build_transactions_pdf(transactions, month, total_income, total_expense, savings)
    filename_month = month or "all-months"
    return Response(
        pdf,
        mimetype="application/pdf",
        headers={"Content-Disposition": f"attachment; filename=expense-report-{filename_month}.pdf"},
    )


# -----------------------------
# Add Expense
# -----------------------------
@app.route("/add", methods=["GET", "POST"])
@login_required
def add_expense():
    if request.method == "POST":
        try:
            new_transaction = Transaction(**parse_transaction_form(request.form))
            db.session.add(new_transaction)
            db.session.commit()
            flash("Transaction added successfully!", "success")
            return redirect("/")
        except ValueError as e:
            flash(f"Error adding transaction: {str(e)}", "danger")
            return redirect(url_for("add_expense"))
    default_date = datetime.now().strftime("%Y-%m-%d")
    return render_template(
        "add_expense.html",
        categories=CATEGORIES,
        transaction_types=TRANSACTION_TYPES,
        people=PEOPLE,
        default_date=default_date,
    )

# -----------------------------
# Delete Transaction
# -----------------------------
@app.route("/delete/<int:id>", methods=["POST"])
@login_required
def delete_transaction(id):
    transaction = Transaction.query.get_or_404(id)
    if transaction:
        db.session.delete(transaction)
        db.session.commit()
        flash("Transaction deleted.", "info")
    else:
        flash("Transaction not found.", "warning")
    return redirect("/")


# -----------------------------
# Edit Transaction
# -----------------------------
@app.route("/edit/<int:id>", methods=["GET", "POST"])
@login_required
def edit_transaction(id):
    transaction = Transaction.query.get_or_404(id)
    if request.method == "POST":
        try:
            for field, value in parse_transaction_form(request.form).items():
                setattr(transaction, field, value)
            db.session.commit()
            flash("Transaction updated!", "success")
            return redirect("/")
        except ValueError as e:
            flash(f"Error updating transaction: {str(e)}", "danger")
            return redirect(url_for("edit_transaction", id=id))
    return render_template(
        "edit_expense.html",
        transaction=transaction,
        categories=CATEGORIES,
        transaction_types=TRANSACTION_TYPES,
        people=PEOPLE,
    )

# Create DB tables
with app.app_context():
    db.create_all()


if __name__ == "__main__":
    app.run(debug=True)
