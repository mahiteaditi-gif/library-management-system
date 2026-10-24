'use strict';

const $ = (s, r = document) => r.querySelector(s);
const view = $('#view');
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = d => d ? new Date(d + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }) : '';
const code = id => 'LB-' + String(id).padStart(4, '0');
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
const daysUntil = d => {
  const t = new Date(); t.setHours(0, 0, 0, 0);
  return Math.round((new Date(d + 'T00:00:00') - t) / 86400000);
};

const COLORS = ['#16284A', '#8A2E3B', '#1F6F6B', '#7B4B94', '#B8620F', '#3A5A2C', '#2E5C9A', '#5B4636'];
const hash = s => [...String(s)].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7);
const coverColor = key => COLORS[hash(key) % COLORS.length];
const coverClass = key => 'p' + (hash('x' + key) % 3);

let policy = { loanDays: 14, maxLoans: 3, finePerDay: 2 };

async function api(path, data) {
  const res = await fetch(path, data ? { method: 'POST', body: new URLSearchParams(data) } : {});
  const json = await res.json().catch(() => ({}));
  if (res.status === 401 && path !== '/api/login') { showLogin(); throw new Error('Please sign in.'); }
  if (!res.ok) throw new Error(json.error || 'Something went wrong. Try again.');
  return json;
}

let toastTimer;
function toast(msg, error = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'show' + (error ? ' error' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.className = ''; }, 3200);
}
const fail = e => toast(e.message, true);

// ---------- dialogs ----------
const dlg = $('#dlg'), cfm = $('#cfm');
function openDialog(html) { dlg.innerHTML = html; if (!dlg.open) dlg.showModal(); return dlg; }
function closeDialog() { if (dlg.open) dlg.close(); }
function confirmBox(title, message, okLabel) {
  return new Promise(resolve => {
    cfm.innerHTML = `<h2>${esc(title)}</h2><p class="lead">${esc(message)}</p>
      <div class="actions"><button class="btn" value="no">Cancel</button><button class="btn primary" value="yes">${esc(okLabel)}</button></div>`;
    cfm.onclick = e => {
      const v = e.target.closest('button')?.value;
      if (v) { cfm.close(); resolve(v === 'yes'); }
    };
    cfm.oncancel = () => resolve(false);
    cfm.showModal();
  });
}

// ---------- sign in / out ----------
function showLogin() {
  $('#app').hidden = true;
  $('#login').hidden = false;
  $('#mosaic').innerHTML = Array.from({ length: 160 }, (_, i) =>
    `<i style="--c:${COLORS[(i * 7 + (i >> 3)) % COLORS.length]}"></i>`).join('');
}
function showApp(user) {
  $('#login').hidden = true;
  $('#app').hidden = false;
  $('#whoami').textContent = 'Signed in as ' + user;
  route();
}
$('#login-form').addEventListener('submit', async e => {
  e.preventDefault();
  const err = $('#login-error');
  err.textContent = '';
  try {
    const r = await api('/api/login', Object.fromEntries(new FormData(e.target)));
    e.target.reset();
    showApp(r.user);
  } catch (ex) { err.textContent = ex.message; }
});
$('#logout').addEventListener('click', async () => {
  try { await api('/api/logout', {}); } catch (_) {}
  showLogin();
});

// ---------- router ----------
const pages = { desk, catalogue, circulation, members };
const titles = { desk: 'Desk', catalogue: 'Books', circulation: 'Issue & return', members: 'Members' };
async function route() {
  const [name, qs] = (location.hash.slice(2) || 'desk').split('?');
  const page = pages[name] ? name : 'desk';
  document.querySelectorAll('.rail nav a').forEach(a => {
    if (a.dataset.page === page) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  document.title = titles[page] + ' · Library Management System';
  try { await pages[page](new URLSearchParams(qs || '')); }
  catch (e) {
    if (e.message !== 'Please sign in.')
      view.innerHTML = `<div class="empty"><strong>This page didn't load.</strong>${esc(e.message)} <button class="linklike" onclick="route()">Try again</button></div>`;
  }
}
window.addEventListener('hashchange', route);

// ---------- desk ----------
async function desk() {
  const [s, overdue, recent] = await Promise.all([
    api('/api/stats'), api('/api/loans?status=overdue'), api('/api/loans?status=all&limit=8')]);
  policy = s;
  const head = s.overdue ? `${plural(s.overdue, 'book is', 'books are')} overdue.` : 'Nothing is overdue.';
  view.innerHTML = `
    <header class="page-head">
      <h1>${head}</h1>
      <p class="muted">${s.out} of ${s.copies} copies are out with members. The library has ${s.titles} titles and ${s.members} members. Late fines collected so far: ₹${s.finesCollected}.</p>
    </header>
    <section>
      <h2>Overdue books</h2>
      ${overdue.length ? `<div class="overdue-list">${overdue.map(l => `
        <div class="overdue">
          <div><div class="t">${esc(l.title)}</div>
            <div class="m">${esc(l.member)} · was due ${fmt(l.due_on)} · <span class="late">${plural(l.daysLate, 'day', 'days')} late</span></div></div>
          <div class="fine"><b>₹${l.fine}</b>fine so far</div>
          <button class="btn small" data-return="${l.id}">Mark returned</button>
        </div>`).join('')}</div>` : `<div class="empty"><strong>All books are on time.</strong>Overdue books will show up here with the fine each one has built up.</div>`}
    </section>
    <section>
      <h2>Latest activity</h2>
      <ul class="feed">${recent.map(l => l.returned_on
        ? `<li><span>${esc(l.member)} returned <em>${esc(l.title)}</em>${l.fine ? ` and paid ₹${l.fine} in fines` : ''}</span><time>${fmt(l.returned_on)}</time></li>`
        : `<li><span>${esc(l.member)} borrowed <em>${esc(l.title)}</em></span><time>${fmt(l.issued_on)}</time></li>`).join('')}</ul>
    </section>`;
  view.onclick = e => doReturn(e, desk);
}

async function doReturn(e, reload) {
  const btn = e.target.closest('[data-return]');
  if (!btn) return;
  btn.disabled = true;
  try {
    const r = await api('/api/return', { loan_id: btn.dataset.return });
    toast(r.fine ? `Returned ${plural(r.daysLate, 'day', 'days')} late. Collect a fine of ₹${r.fine}.` : 'Returned on time. No fine.');
    reload();
  } catch (ex) { fail(ex); btn.disabled = false; }
}

// ---------- catalogue ----------
async function catalogue() {
  view.onclick = null;
  view.innerHTML = `
    <header class="page-head row">
      <div><h1>Books</h1><p class="muted" id="cat-sub"></p></div>
      <button class="btn primary" id="add-book">Add a book</button>
    </header>
    <input class="search" id="q" type="search" placeholder="Search by title, author or category" aria-label="Search books">
    <div id="shelves"></div>`;
  const q = $('#q'); let books = [], seq = 0;

  async function load() {
    const mine = ++seq;
    const rows = await api('/api/books?q=' + encodeURIComponent(q.value));
    if (mine !== seq) return;
    books = rows;
    $('#cat-sub').textContent = `${plural(rows.length, 'title', 'titles')} ${q.value ? 'match your search' : 'in the library'}. Greyed covers have no copy available right now.`;
    if (!rows.length) {
      $('#shelves').innerHTML = `<div class="empty"><strong>No books found${q.value ? ' for “' + esc(q.value) + '”' : ''}.</strong>Check the spelling, or add the book to the catalogue.</div>`;
      return;
    }
    const groups = {};
    rows.forEach(b => (groups[b.category] ||= []).push(b));
    $('#shelves').innerHTML = Object.entries(groups).map(([cat, list]) => `
      <div class="cat-block">
        <div class="cat-head"><h2>${esc(cat)}</h2><span>${plural(list.length, 'title', 'titles')}</span></div>
        <div class="grid">${list.map(b => `<button class="book${b.available ? '' : ' out'}" data-id="${b.id}"
          aria-label="${esc(b.title)} by ${esc(b.author)}, ${b.available} of ${b.copies} available">
          <span class="cover ${coverClass(b.title)}" style="--c:${coverColor(b.category)}"><b>${esc(b.title)}</b><small>${esc(b.author)}</small></span>
          <span class="stock">${b.available ? `${b.available} of ${b.copies} available` : 'All copies issued'}</span></button>`).join('')}</div>
      </div>`).join('');
  }

  let t; q.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => load().catch(fail), 200); });
  $('#add-book').onclick = () => bookForm(null, load);
  $('#shelves').onclick = e => {
    const bk = e.target.closest('.book');
    if (bk) bookDetail(books.find(b => b.id == bk.dataset.id), load);
  };
  await load();
}

function bookDetail(b, reload) {
  openDialog(`
    <h2>${esc(b.title)}</h2>
    <p class="muted">${esc(b.author)}</p>
    <dl class="facts">
      <dt>Category</dt><dd>${esc(b.category)}</dd>
      <dt>Accession no.</dt><dd>${code(b.id)}</dd>
      <dt>On the shelf</dt><dd>${b.available} of ${b.copies} ${b.copies === 1 ? 'copy' : 'copies'}</dd>
    </dl>
    <div class="actions">
      <button class="btn danger" id="d-del">Delete</button>
      <button class="btn" id="d-edit">Edit</button>
      <button class="btn primary" id="d-issue" ${b.available ? '' : 'disabled'}>Issue this book</button>
    </div>`);
  $('#d-issue').onclick = () => { closeDialog(); location.hash = '#/circulation?book=' + b.id; };
  $('#d-edit').onclick = () => bookForm(b, reload);
  $('#d-del').onclick = async () => {
    closeDialog();
    if (!await confirmBox('Delete this book?', `“${b.title}” and its borrowing history will be removed. This can't be undone.`, 'Delete book')) return;
    try { await api('/api/books/delete', { id: b.id }); toast('Book deleted.'); reload(); }
    catch (e) { fail(e); }
  };
}

async function bookForm(b, reload) {
  const cats = [...new Set((await api('/api/books')).map(x => x.category))];
  openDialog(`
    <h2>${b ? 'Edit book' : 'Add a book'}</h2>
    <form id="bf">
      <label>Title<input name="title" required maxlength="150" value="${esc(b?.title)}"></label>
      <label>Author<input name="author" required maxlength="120" value="${esc(b?.author)}"></label>
      <label>Category<input name="category" required maxlength="40" list="cats" value="${esc(b?.category)}"></label>
      <datalist id="cats">${cats.map(c => `<option value="${esc(c)}">`).join('')}</datalist>
      <label>Total copies<input name="copies" type="number" min="1" max="99" required value="${b ? b.copies : 1}"></label>
      <p class="form-error" id="bf-err" role="alert"></p>
      <div class="actions"><button class="btn" type="button" id="bf-cancel">Cancel</button><button class="btn primary" type="submit">${b ? 'Save changes' : 'Add book'}</button></div>
    </form>`);
  $('#bf-cancel').onclick = closeDialog;
  $('#bf').onsubmit = async e => {
    e.preventDefault();
    const data = Object.fromEntries(new FormData(e.target));
    if (b) data.id = b.id;
    try {
      await api(b ? '/api/books/update' : '/api/books', data);
      closeDialog(); toast(b ? 'Changes saved.' : 'Book added.'); reload();
    } catch (ex) { $('#bf-err').textContent = ex.message; }
  };
}

// ---------- issue & return ----------
async function circulation(params) {
  view.onclick = null;
  policy = await api('/api/stats');
  view.innerHTML = `
    <header class="page-head"><h1>Issue &amp; return</h1>
      <p class="muted">Loans run ${policy.loanDays} days. Late books cost ₹${policy.finePerDay} a day. Each member can hold ${policy.maxLoans} books.</p></header>
    <div class="issue-grid">
      <form class="panel" id="issue-form">
        <h2>Issue a book</h2>
        <label>Member<select name="member_id" id="sel-member" required></select></label>
        <label>Book<select name="book_id" id="sel-book" required></select></label>
        <p class="form-error" id="issue-err" role="alert"></p>
        <button class="btn primary wide" type="submit">Issue book</button>
      </form>
      <div id="slip-area" class="card-slot"><p class="card-hint">After you issue a book, its library card with the due date appears here.</p></div>
    </div>
    <section>
      <h2>Loans</h2>
      <div class="tabs" id="tabs">
        ${[['active', 'Out now'], ['overdue', 'Overdue'], ['returned', 'Returned'], ['all', 'All']].map(([v, l]) =>
          `<button data-s="${v}" aria-pressed="${v === 'active'}">${l}</button>`).join('')}
      </div>
      <div id="loans"></div>
    </section>`;
  let status = 'active';

  async function fillSelects(preBook) {
    const [members, books] = await Promise.all([api('/api/members'), api('/api/books')]);
    $('#sel-member').innerHTML = `<option value="">Choose a member</option>` + members.map(m =>
      `<option value="${m.id}">${esc(m.name)} (${m.borrowing} of ${policy.maxLoans} books)</option>`).join('');
    const avail = books.filter(b => b.available > 0);
    $('#sel-book').innerHTML = `<option value="">Choose a book</option>` + avail.map(b =>
      `<option value="${b.id}">${esc(b.title)} · ${esc(b.author)} (${b.available} left)</option>`).join('');
    if (preBook) $('#sel-book').value = preBook;
  }
  async function loadLoans() {
    const rows = await api('/api/loans?status=' + status);
    if (!rows.length) {
      $('#loans').innerHTML = `<div class="empty"><strong>${{ active: 'No books are out.', overdue: 'Nothing is overdue.', returned: 'No returns yet.', all: 'No loans yet.' }[status]}</strong>Issued books will be listed here.</div>`;
      return;
    }
    $('#loans').innerHTML = `<div class="table-wrap"><table>
      <thead><tr><th>Book</th><th>Member</th><th>Issued</th><th>Due</th><th>Status</th><th></th></tr></thead>
      <tbody>${rows.map(l => {
        let st;
        if (l.state === 'returned') st = `Returned ${fmt(l.returned_on)}${l.fine ? `<span class="sub">₹${l.fine} fine</span>` : ''}`;
        else if (l.state === 'overdue') st = `<span class="late">${plural(l.daysLate, 'day', 'days')} late</span><span class="sub">₹${l.fine} fine so far</span>`;
        else { const d = daysUntil(l.due_on); st = d === 0 ? 'Due today' : `Due in ${plural(d, 'day', 'days')}`; }
        return `<tr><td>${esc(l.title)}<span class="sub">${esc(l.author)}</span></td><td>${esc(l.member)}</td>
          <td>${fmt(l.issued_on)}</td><td>${fmt(l.due_on)}</td><td>${st}</td>
          <td class="num">${l.returned_on ? '' : `<button class="btn small" data-return="${l.id}">Mark returned</button>`}</td></tr>`;
      }).join('')}</tbody></table></div>`;
  }
  const refresh = async () => { await Promise.all([fillSelects(), loadLoans()]); };

  $('#tabs').onclick = e => {
    const b = e.target.closest('button'); if (!b) return;
    status = b.dataset.s;
    document.querySelectorAll('#tabs button').forEach(x => x.setAttribute('aria-pressed', x === b));
    loadLoans().catch(fail);
  };
  $('#loans').onclick = e => doReturn(e, refresh);
  $('#issue-form').onsubmit = async e => {
    e.preventDefault();
    $('#issue-err').textContent = '';
    try {
      const r = await api('/api/issue', Object.fromEntries(new FormData(e.target)));
      $('#slip-area').innerHTML = `
        <div class="lib-card">
          <span class="stamp">Issued ${new Date().toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}</span>
          <h3>${esc(r.title)}</h3><p class="who">${esc(r.member)}</p>
          <dl><dt>Borrowed on</dt><dd>${fmt(r.issued_on)}</dd><dt>Return by</dt><dd>${fmt(r.due_on)}</dd>
          <dt>Late fine</dt><dd>₹${policy.finePerDay} a day</dd></dl>
        </div>`;
      toast('Book issued.');
      await refresh();
    } catch (ex) { $('#issue-err').textContent = ex.message; }
  };
  await Promise.all([fillSelects(params.get('book')), loadLoans()]);
}

// ---------- members ----------
async function members() {
  view.onclick = null;
  view.innerHTML = `
    <header class="page-head row">
      <div><h1>Members</h1><p class="muted" id="mem-sub"></p></div>
      <button class="btn primary" id="add-member">Add a member</button>
    </header>
    <input class="search" id="q" type="search" placeholder="Search by name or email" aria-label="Search members">
    <div id="list"></div>`;
  const q = $('#q'); let seq = 0;
  async function load() {
    const mine = ++seq;
    const rows = await api('/api/members?q=' + encodeURIComponent(q.value));
    if (mine !== seq) return;
    $('#mem-sub').textContent = plural(rows.length, 'member', 'members') + (q.value ? ' match your search.' : ' registered.');
    if (!rows.length) {
      $('#list').innerHTML = `<div class="empty"><strong>No members found.</strong>Check the spelling, or add this person as a new member.</div>`;
      return;
    }
    $('#list').innerHTML = `<div class="table-wrap"><table>
      <thead><tr><th>Name</th><th>Contact</th><th>Member since</th><th class="num">Books out</th><th></th></tr></thead>
      <tbody>${rows.map(m => `<tr><td>${esc(m.name)}</td>
        <td>${esc(m.email || '—')}<span class="sub">${esc(m.phone || '')}</span></td>
        <td>${fmt(m.joined_on)}</td><td class="num">${m.borrowing}</td>
        <td class="num"><button class="btn small danger" data-del="${m.id}" data-name="${esc(m.name)}">Remove</button></td></tr>`).join('')}</tbody>
    </table></div>`;
  }
  let t; q.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => load().catch(fail), 200); });
  $('#list').onclick = async e => {
    const b = e.target.closest('[data-del]'); if (!b) return;
    if (!await confirmBox('Remove this member?', `${b.dataset.name} and their borrowing history will be removed.`, 'Remove member')) return;
    try { await api('/api/members/delete', { id: b.dataset.del }); toast('Member removed.'); load(); }
    catch (ex) { fail(ex); }
  };
  $('#add-member').onclick = () => {
    openDialog(`
      <h2>Add a member</h2>
      <form id="mf">
        <label>Full name<input name="name" required maxlength="100"></label>
        <label>Email <span class="muted">(optional)</span><input name="email" type="email"></label>
        <label>Phone <span class="muted">(optional)</span><input name="phone" type="tel"></label>
        <p class="form-error" id="mf-err" role="alert"></p>
        <div class="actions"><button class="btn" type="button" id="mf-cancel">Cancel</button><button class="btn primary" type="submit">Add member</button></div>
      </form>`);
    $('#mf-cancel').onclick = closeDialog;
    $('#mf').onsubmit = async e => {
      e.preventDefault();
      try { await api('/api/members', Object.fromEntries(new FormData(e.target))); closeDialog(); toast('Member added.'); load(); }
      catch (ex) { $('#mf-err').textContent = ex.message; }
    };
  };
  await load();
}

// ---------- start ----------
api('/api/me').then(r => showApp(r.user)).catch(() => showLogin());
