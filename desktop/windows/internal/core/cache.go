package core

import (
	"container/list"
	"sync"
	"time"
)

type cacheEntry struct {
	key        string
	data       []byte
	exp        time.Time
	staleUntil time.Time
	total      time.Duration
	hits       int
	refreshing bool
}

type CacheHit struct {
	Data     []byte
	Stale    bool
	TTLLeft  int64
	Prefetch bool
}

// Cache: LRU dengan TTL, serve-stale (RFC 8767) dan prefetch nama populer.
type Cache struct {
	mu  sync.Mutex
	max int
	ll  *list.List
	m   map[string]*list.Element
}

func NewCache(max int) *Cache { return &Cache{max: max, ll: list.New(), m: map[string]*list.Element{}} }

func (c *Cache) Get(key string) *CacheHit {
	c.mu.Lock()
	defer c.mu.Unlock()
	el, ok := c.m[key]
	if !ok {
		return nil
	}
	e := el.Value.(*cacheEntry)
	now := time.Now()
	if now.After(e.staleUntil) {
		c.ll.Remove(el)
		delete(c.m, key)
		return nil
	}
	c.ll.MoveToFront(el)
	stale := now.After(e.exp)
	left := int64(30)
	if !stale {
		left = int64(e.exp.Sub(now) / time.Second)
		if left < 1 {
			left = 1
		}
	}
	e.hits++
	thr := int64(e.total / time.Second / 10)
	if thr < 3 {
		thr = 3
	}
	pre := !stale && !e.refreshing && e.hits >= 3 && left <= thr
	if pre {
		e.refreshing = true
	}
	d := make([]byte, len(e.data))
	copy(d, e.data)
	return &CacheHit{Data: d, Stale: stale, TTLLeft: left, Prefetch: pre}
}

func (c *Cache) Put(key string, data []byte, ttl, stale time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	e := &cacheEntry{key: key, data: append([]byte(nil), data...), exp: now.Add(ttl), staleUntil: now.Add(ttl + stale), total: ttl}
	if el, ok := c.m[key]; ok {
		el.Value = e
		c.ll.MoveToFront(el)
		return
	}
	c.m[key] = c.ll.PushFront(e)
	for c.ll.Len() > c.max {
		last := c.ll.Back()
		c.ll.Remove(last)
		delete(c.m, last.Value.(*cacheEntry).key)
	}
}

func (c *Cache) Clear() {
	c.mu.Lock()
	c.ll.Init()
	c.m = map[string]*list.Element{}
	c.mu.Unlock()
}

func (c *Cache) Len() int { c.mu.Lock(); defer c.mu.Unlock(); return c.ll.Len() }
