// A-0 authoring corpus, program 1: a validated form.
//
// Exercises: scope-owned cells, derived validation, errors shown only after a
// field is left, an async submit command whose enablement comes from the
// command, explicit rejection with `UiTxn.require`, and form semantics.
package sirin.examples.form

import sirin.*

final case class Account(name: String, email: String, age: Int, newsletter: Boolean)
final case class AccountId(value: String)

trait AccountService:
  def create(account: Account): Work[AccountId]

object Rules:
  def name(raw: String): Either[String, String] =
    val t = raw.trim
    if t.isEmpty then Left("Enter your name.") else Right(t)

  def email(raw: String): Either[String, String] =
    val t = raw.trim
    if t.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") then Right(t) else Left("Enter an email address like name@example.org.")

  def age(raw: String): Either[String, Int] =
    raw.trim.toIntOption.filter(a => a >= 13 && a < 150).toRight("Enter an age between 13 and 149.")

/** One text field's state: raw text, whether it was left once, the parse.
  * Owned by the scope passed in; nothing here needs cleanup.
  */
final class Field[A](scope: Scope, parse: String => Either[String, A]):
  val text: Cell[String]                = scope.cell("")
  val touched: Cell[Boolean]            = scope.cell(false)
  val parsed: Signal[Either[String, A]] = text.map(parse)

  /** The error to display: only once the user has left the field. */
  val shown: Signal[Option[String]] =
    (parsed, touched).mapN((p, t) => if t then p.left.toOption else None)

  def view(label: String): View =
    TextField.bind(text).label(label).error(shown).onBlur(touched.set(true))

val SignUpForm: Component[AccountService] = Component("SignUpForm") { (scope, service) =>
  val name       = Field(scope, Rules.name)
  val email      = Field(scope, Rules.email)
  val age        = Field(scope, Rules.age)
  val newsletter = scope.cell(false)

  val account: Signal[Either[String, Account]] =
    (name.parsed, email.parsed, age.parsed).mapN { (n, e, a) =>
      for n <- n; e <- e; a <- a yield Account(n, e, a, newsletter = false)
    }.zip(newsletter).map((acc, nl) => acc.map(_.copy(newsletter = nl)))

  // AWKWARD (REVIEW #1): the command cannot see its own `running` signal while
  // it is being constructed, so "disabled while submitting" needs a second cell.
  val submitting  = scope.cell(false)
  val created     = scope.cell(Option.empty[AccountId])
  val submitError = scope.cell(Option.empty[String])

  val submit: Command[Unit, AccountId] =
    scope.command[Unit, Account, AccountId](
      id = "signup.submit",
      label = "Create account",
      freshness = Freshness.Serial,
      enabled = account.map(_.isRight) && !submitting
    ) { _ =>
      // The dispatch boundary has already checked `enabled`; this guard
      // documents the invariant and rejects the transaction if it is broken.
      val acc = account.get.getOrElse(UiTxn.reject("submit with an invalid form"))
      submitting.set(true)
      submitError.set(None)
      Job(acc, service.get.create(acc))
    } { (_, outcome) =>
      submitting.set(false)
      outcome match
        case Outcome.Succeeded(id) => created.set(Some(id))
        case Outcome.Failed(e)     => submitError.set(Some(s"Could not create the account: ${e.getMessage}"))
        case Outcome.Cancelled     => ()
    }

  Column(
    Text("Create an account").style(TextStyle.Title),
    name.view("Name"),
    email.view("Email"),
    age.view("Age"),
    Checkbox.bind(newsletter, "Send me the occasional newsletter"),
    Switch(submitError) {
      case Some(message) =>
        Text(message).color(ColorRole.Danger).semantics(Semantics(Role.Alert, live = Live.Assertive))
      case None => View.empty
    },
    Switch(created) {
      case Some(id) => Text(s"Account ${id.value} created.").semantics(Semantics(Role.Status, live = Live.Polite))
      case None     => View.empty
    },
    Row(Spacer().grow, Button(submit).primary).spacing(Spacing.small)
  ).spacing(Spacing.medium)
    .padding(Spacing.large)
    .semantics(Semantics(Role.Form, name = Some("Create an account")))
    .shortcut(Chord(Key.Enter, Mods.Primary), submit)
}

object InMemoryAccounts extends AccountService:
  def create(account: Account): Work[AccountId] = Work {
    if summon[Cancellation].cancelled then throw new InterruptedException("cancelled")
    AccountId(account.email.takeWhile(_ != '@'))
  }

@main def validatedForm(): Unit =
  Application.run { _ =>
    Seq(Window("Sign up", MenuBar.empty)(SignUpForm(InMemoryAccounts)))
  }
