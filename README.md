# TaskManager

TaskManager用于管理定时任务，它的功能仅限于管理，不包括生成和运行（比如每天定时运行）。

## 运行模式

TaskManager提供任务队列，实现的executor可以通过HTTP的API来获取和控制任务，用户可以用任何可用的语言实现Executor。从这个角度来说，TaskManager主要是一个任务队列。

#### 特性

- 高吞吐。在设计上，TaskManager采用批量操作，一次性处理多个请求，不会有任务积压在任务队列，这使得它能同事hold住大量请求
- 定时触发。TaskManager可以指定某个任务在某个时间点之后运行，用户可以把每个月需要运行的任务全部放到队列中
- 并行化和分布式。TaskManager通过HTTP提供服务，不限定executor的数量。用户甚至可以把任务放到大量的Raspberry PI上
- 任务重试和超时。任务可以被重试和超时
- 任务恢复。可以恢复之前失败的任务
- 不同类型的任务。任务具有类型，可以通过类型绑定相应的处理逻辑。
- 多任务队列。TaskManager提供了很多任务的pool，可以对executor分门别类


#### 运行

```bash
bin/activator run
```