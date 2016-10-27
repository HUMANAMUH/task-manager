package net.earthson.task

import scala.collection.mutable.ArrayBuffer

/**
  * Created by earthson on 10/26/16.
  */
class TQueue[T] {
  var base: List[T] = Nil
  var cache: List[T] = Nil

  def enqueue(v: T): Unit = {
    cache = v :: cache
  }

  def size = base.size + cache.size

  def isEmpty = this.size == 0

  def nonEmpty = this.size > 0

  private def cache_to_base() = {
    if (base.isEmpty && cache.nonEmpty) {
      while (cache.nonEmpty) {
        base = cache.head :: base
        cache = cache.tail
      }
    }
  }

  def dequeue(): Option[T] = {
    cache_to_base()
    val res = base.headOption
    if (base.nonEmpty) {
      base = base.tail
    }
    res
  }

  def head: T = {
    cache_to_base()
    base.head
  }

  def headOption: Option[T] = {
    cache_to_base()
    base.headOption
  }

  def tail: TQueue[T] = {
    cache_to_base()
    val res = new TQueue[T]
    res.base = base.tail
    res.cache = cache
    res
  }

  def dequeueBulk(cond: T => Boolean): Seq[T] = {
    val res = new ArrayBuffer[T]
    var newCache: List[T] = Nil
    def do_base_act() = {
      while (base.nonEmpty) {
        if (cond(base.head)) {
          res.append(base.head)
        } else {
          newCache = base.head :: newCache
        }
        base = base.tail
      }
    }
    do_base_act()
    cache_to_base()
    do_base_act()
    cache = newCache
    res
  }
}
