const $ = (id) => document.getElementById(id);

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers || {}) }
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || body.message || "Request failed");
  return body;
}

function updateFields() {
  const type = $("type").value;
  $("source-field").style.display = type === "DEPOSIT" ? "none" : "grid";
  $("destination-field").style.display = type === "WITHDRAWAL" ? "none" : "grid";
}

async function refreshHealth() {
  try {
    const health = await api("/api/health");
    $("health-text").textContent = health.database ? "Database online" : "Database degraded";
    document.querySelector(".status").classList.toggle("online", health.database);
  } catch (error) {
    $("health-text").textContent = "Database offline";
  }
}

async function refreshAccounts() {
  const accounts = await api("/api/accounts?limit=12");
  $("accounts").innerHTML = accounts.map(account => `
    <article class="account">
      <span class="account-status">${account.status}</span>
      <span class="account-id">Account ${account.accountId}</span>
      <span class="balance">${Number(account.balance).toLocaleString(undefined, {minimumFractionDigits: 2, maximumFractionDigits: 2})}</span>
    </article>
  `).join("");
}

async function refreshTransactions() {
  const transactions = await api("/api/transactions?limit=50");
  $("transactions").innerHTML = transactions.map(tx => {
    const accounts = tx.type === "TRANSFER"
      ? `${tx.sourceAccountId || "-"} → ${tx.destinationAccountId || "-"}`
      : `${tx.destinationAccountId || tx.sourceAccountId || "-"}`;
    return `<tr>
      <td><strong>${escapeHtml(tx.transactionId)}</strong></td>
      <td>${tx.type}</td>
      <td>${Number(tx.amount || 0).toLocaleString(undefined, {minimumFractionDigits: 2})}</td>
      <td><span class="badge ${tx.status}">${tx.status}</span></td>
      <td>${accounts}</td>
      <td class="reason">${escapeHtml(tx.reason || "Completed successfully")}</td>
    </tr>`;
  }).join("");
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;" }[char]));
}

async function refreshAll() {
  await Promise.all([refreshHealth(), refreshAccounts(), refreshTransactions()]);
}

$("type").addEventListener("change", updateFields);
$("refresh-button").addEventListener("click", refreshAll);
$("reset-button").addEventListener("click", async () => {
  if (!confirm("Reset all accounts and transaction history?")) return;
  await api("/api/reset", { method: "POST" });
  await refreshAll();
});

$("transaction-form").addEventListener("submit", async event => {
  event.preventDefault();
  const type = $("type").value;
  const payload = {
    transactionId: $("transaction-id").value.trim(),
    type,
    sourceAccountId: type === "DEPOSIT" ? null : Number($("source-account").value),
    destinationAccountId: type === "WITHDRAWAL" ? null : Number($("destination-account").value),
    amount: $("amount").value
  };
  const result = $("result");
  try {
    const response = await api("/api/transactions", { method: "POST", body: JSON.stringify(payload) });
    result.hidden = false;
    result.className = `result ${response.status.toLowerCase()}`;
    result.innerHTML = `<strong>${response.status}</strong>${response.duplicate ? " · Duplicate replay" : ""}<br>${escapeHtml(response.reason || "Transaction completed")}`;
    await Promise.all([refreshAccounts(), refreshTransactions()]);
  } catch (error) {
    result.hidden = false;
    result.className = "result rejected";
    result.textContent = error.message;
  }
});

updateFields();
refreshAll();
