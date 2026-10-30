package sirin.desktop

import sirin.host.HostInput

class OwnerInputQueueSuite extends munit.FunSuite:
  test("closing observes prior text and input order before deciding dirty state") {
    val queue=new OwnerInputQueue[Int];var text="";var seen=Vector.empty[Int]
    queue.add(1,Some(HostInput.TextInput("latest edit")));queue.add(2,None)
    assert(queue.closing)
    assert(!queue.drain(() => true,_ => false)((id,event) => {
      seen=seen:+id;event match
        case HostInput.TextInput(value) => text=value
        case _ => ()
      true
    },id => {seen=seen:+id;assertEquals(text,"latest edit");false}))
    assertEquals(seen,Vector(1,2));assert(!queue.nonEmpty)
  }
  test("geometry-dependent facts hold the close boundary until presentation is ready") {
    val queue=new OwnerInputQueue[Int];var ready=false;var seen=Vector.empty[Int]
    queue.add(1,Some(HostInput.Down(2,3)));queue.add(2,None)
    def drain()=queue.drain(() => ready,_ => true)((id,_) => {seen=seen:+id;true},id => {seen=seen:+id;true})
    assert(drain());assert(seen.isEmpty);assert(queue.closing)
    ready=true;assert(drain());assertEquals(seen,Vector(1,2));assert(!queue.closing)
    queue.add(3,Some(HostInput.TextInput("after refused close")));assert(drain());assertEquals(seen,Vector(1,2,3))
  }
  test("retirement, close boundaries and capacity refuse out-of-order or unbounded input") {
    val queue=new OwnerInputQueue[Int](2)
    queue.add(1,Some(HostInput.TextInput("a")));queue.add(2,None)
    intercept[IllegalArgumentException](queue.add(3,Some(HostInput.TextInput("late"))))
    assert(!queue.drain(() => true,_ => false)((_,_) => false,_ => fail("close must not run after retirement")))
    assert(!queue.closing);assert(!queue.nonEmpty)
    queue.add(1,Some(HostInput.TextInput("a")));queue.add(2,Some(HostInput.TextInput("b")))
    intercept[IllegalArgumentException](queue.add(3,None))
  }
