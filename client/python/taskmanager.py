#!/usr/bin/env python3

import json
import time
import logging
import inspect
import functools
import concurrent
import aiohttp
import asyncio
import traceback
import async_timeout
import yaml

logging.basicConfig(level=logging.DEBUG)


class TaskExecutor(object):
    def __init__(self, config, loop=None):
        self.task_mapping = dict()
        self.loop = loop if loop is not None else asyncio.get_event_loop()
        self.config = config
        self.server_url = config["server_url"]
        self.request_timeout = config["request_timeout"]
        self.pool = config["pool"]
        self.try_limit = config["try_limit"]
        self.task_timeout = config["task_timeout"]
        self.num_worker = config["num_worker"]
        self.exit_when_done = config["exit_when_done"]

    @staticmethod
    def load(config_file, loop=None):
        with open(config_file, "r") as f:
            return TaskExecutor(yaml.load(f.read()), loop=loop)

    def register(self, type):
        def wrapper(f):
            self.task_mapping[type] = f
            return f
        return wrapper

    def terminate(self):
        # TODO: block/async logic
        self.terminate_flag = True

    def __enter__(self):
        self.session = aiohttp.ClientSession(loop=self.loop)
        self.executor = concurrent.futures.ThreadPoolExecutor(max_workers=self.num_worker)
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.session.__exit__(exc_type, exc_val, exc_tb)
        self.executor.__exit__(ext_type, exc_val, exc_tb)

    async def run(self):
        await asyncio.gather(*[texec.worker(i) for i in range(self.num_worker)])

    async def get(self, url):
        with async_timeout.timeout(self.request_timeout):
            async with self.session.get(url) as response:
                return await response.text()

    async def post_json(self, path, obj):
        with async_timeout.timeout(self.request_timeout):
            async with self.session.post("%s%s" % (self.server_url, path), data=json.dumps(obj).encode("utf-8"),
                                         headers={'content-type': 'application/json'}) as response:
                return await response.text()

    async def task_create(self, type, key, *args, **kwargs):
        obj = {
            "pool": self.pool,
            "type": type,
            "key": key,
            "options": json.dumps({"args": args, "kwargs": kwargs}),
            "tryLimit": self.try_limit,
            "timeout": self.task_timeout
        }
        return await self.post_json("/task/create", obj)

    async def task_fetch(self):
        obj = {
            "pool": self.pool,
            "limit": 1
        }
        return await post_json("%s/task/start" % self.server_url, obj)

    async def task_delete(self, id):
        obj = {
            "id": id
        }
        return await post_json("%s/task/delete" % self.server_url, obj)

    async def task_success(self, id):
        obj = {
            "id": id
        }
        return await post_json("%s/task/success" % base_url, obj)

    async def task_fail(self, id, log):
        obj = {
            "id": id,
            "log": log
        }
        return await post_json("%s/task/fail" % base_url, obj)

    async def worker(self, i):
        logging.info("worker(%d) started" % i)
        while True:
            tasks = json.loads(await task_fetch())
            if self.terminate_flag:
                return
            if len(tasks) == 0:
                if self.exit_when_done:
                    logging.info("worker(%d) done. exit" % i)
                    return
                else:
                    logging.info("task all done, waiting")
                    await asyncio.sleep(5)
            for t in tasks:
                if t["type"] in task_mapping:
                    f = task_mapping[t["type"]]
                    opts = json.loads(t["options"])
                    try:
                        fut = self.loop.run_in_executor(self.executor,
                                                        functools.partial(f, *opts["args"], **opts["kwargs"]))
                        v = await asyncio.wait_for(fut, None)
                        if inspect.isawaitable(v):
                            res = await v
                            logging.debug("res: %s" % res)
                        await task_success(t["id"])
                    except Exception as e:
                        err_trace = traceback.format_exc()
                        logging.error(err_trace)
                        await task_fail(t["id"], err_trace)
                else:
                    logging.warning("Unknown task type: %s" % repr(t))
                    await task_fail(t["id"], traceback.format_exc())
