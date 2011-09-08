namespace Volante
{
    using System;
    using System.Collections;

    public class TestIndexResult : TestResult
    {
        public TimeSpan InsertTime;
        public TimeSpan IndexSearchTime;
        public TimeSpan IterationTime;
        public TimeSpan RemoveTime;
        public ICollection MemoryUsage; // values are of TypeMemoryUsage type
    }

    public class TestIndex : ITest
    {
        public class Root : Persistent
        {
            public IIndex<string, RecordFull> strIndex;
            public IIndex<long, RecordFull> intIndex;
        }

        public void Run(TestConfig config)
        {
            int i;
            int count = config.Count;
            var res = new TestIndexResult();
            config.Result = res;
            IDatabase db = config.GetDatabase();
            if (config.Serializable)
                db.BeginThreadTransaction(TransactionMode.Serializable);

            Root root = (Root)db.Root;
            Tests.Assert(null == root);
            root = new Root();
            root.strIndex = db.CreateIndex<string, RecordFull>(IndexType.Unique);
            root.intIndex = db.CreateIndex<long, RecordFull>(IndexType.Unique);
            db.Root = root;
            var strIndex = root.strIndex;
            Tests.Assert(typeof(string) == strIndex.KeyType);
            var intIndex = root.intIndex;
            Tests.Assert(typeof(long) == intIndex.KeyType);

            DateTime start = DateTime.Now;
            long key = 1999;
            int startWithOne = 0;
            int startWithFive = 0;
            string strFirst = "z";
            string strLast  = "0";
            for (i = 0; i < count; i++)
            {
                RecordFull rec = new RecordFull(key);
                if (rec.StrVal[0] == '1')
                    startWithOne += 1;
                else if (rec.StrVal[0] == '5')
                    startWithFive += 1;
                if (rec.StrVal.CompareTo(strFirst) < 0)
                    strFirst = rec.StrVal;
                else if (rec.StrVal.CompareTo(strLast) > 0)
                    strLast = rec.StrVal;
                intIndex[rec.Int32Val] = rec;
                strIndex[rec.StrVal] = rec;
                if (i % 100 == 0)
                    db.Commit();
                key = (3141592621L * key + 2718281829L) % 1000000007L;
            }

            if (config.Serializable)
            {
                db.EndThreadTransaction();
                db.BeginThreadTransaction(TransactionMode.Serializable);
            }
            else
            {
                db.Commit();
            }

            res.InsertTime = DateTime.Now - start;
            start = System.DateTime.Now;

            key = 1999;
            for (i = 0; i < count; i++)
            {
                RecordFull rec1 = intIndex[key];
                RecordFull rec2 = strIndex[Convert.ToString(key)];
                Tests.Assert(rec1 != null && rec1 == rec2);
                key = (3141592621L * key + 2718281829L) % 1000000007L;
            }
            res.IndexSearchTime = DateTime.Now - start;
            start = System.DateTime.Now;

            key = Int64.MinValue;
            i = 0;
            foreach (RecordFull rec in intIndex)
            {
                Tests.Assert(rec.Int32Val >= key);
                key = rec.Int32Val;
                i += 1;
            }
            Tests.Assert(i == count);

            String strKey = "";
            i = 0;
            foreach (RecordFull rec in strIndex)
            {
                Tests.Assert(rec.StrVal.CompareTo(strKey) >= 0);
                strKey = rec.StrVal;
                i += 1;
            }
            Tests.Assert(i == count);
            res.IterationTime = DateTime.Now - start;
            start = System.DateTime.Now;

            IDictionaryEnumerator de = intIndex.GetDictionaryEnumerator();
            i = VerifyDictionaryEnumerator(de, IterationOrder.AscentOrder);
            Tests.Assert(i == count);

            long mid = 0;
            long max = long.MaxValue;
            de = intIndex.GetDictionaryEnumerator(new Key(mid), new Key(max), IterationOrder.DescentOrder);
            VerifyDictionaryEnumerator(de, IterationOrder.DescentOrder);

            Tests.AssertDatabaseException(() => intIndex.PrefixSearch("1"),
                DatabaseException.ErrorCode.INCOMPATIBLE_KEY_TYPE);
            RecordFull[] recs;
            recs = strIndex.PrefixSearch("1");
            Tests.Assert(startWithOne == recs.Length);
            foreach (var r in recs)
            {
                Tests.Assert(r.StrVal.StartsWith("1"));
            }
            recs = strIndex.PrefixSearch("5");
            Tests.Assert(startWithFive == recs.Length);
            foreach (var r in recs)
            {
                Tests.Assert(r.StrVal.StartsWith("5"));
            }
            recs = strIndex.PrefixSearch("0");
            Tests.Assert(0 == recs.Length);

            recs = strIndex.PrefixSearch("a");
            Tests.Assert(0 == recs.Length);

            recs = strIndex.PrefixSearch(strFirst);
            Tests.Assert(recs.Length >= 1);
            Tests.Assert(recs[0].StrVal == strFirst);
            foreach (var r in recs)
            {
                Tests.Assert(r.StrVal.StartsWith(strFirst));
            }

            recs = strIndex.PrefixSearch(strLast);
            Tests.Assert(recs.Length == 1);
            Tests.Assert(recs[0].StrVal == strLast);

            key = 1999;
            for (i = 0; i < count; i++)
            {
                RecordFull rec = intIndex.Get(key);
                RecordFull removed = intIndex.RemoveKey(key);
                Tests.Assert(removed == rec);
                strIndex.Remove(new Key(System.Convert.ToString(key)), rec);
                rec.Deallocate();
                key = (3141592621L * key + 2718281829L) % 1000000007L;
            }
            res.RemoveTime = DateTime.Now - start;
            db.Close();
        }

        static int VerifyDictionaryEnumerator(IDictionaryEnumerator de, IterationOrder order)
        {
            long prev = long.MinValue;
            if (order == IterationOrder.DescentOrder)
                prev = long.MaxValue;
            int i = 0;
            while (de.MoveNext())
            {
                DictionaryEntry e1 = (DictionaryEntry)de.Current;
                DictionaryEntry e2 = de.Entry;
                Tests.Assert(e1.Equals(e2));
                long k = (long)e1.Key;
                long k2 = (long)de.Key;
                Tests.Assert(k == k2);
                RecordFull v1 = (RecordFull)e1.Value;
                RecordFull v2 = (RecordFull)de.Value;
                Tests.Assert(v1.Equals(v2));
                Tests.Assert(v1.Int32Val == k);
                if (order == IterationOrder.AscentOrder)
                    Tests.Assert(k >= prev);
                else
                    Tests.Assert(k <= prev);
                prev = k;
                i++;
            }
            Tests.VerifyDictionaryEnumeratorDone(de);
            return i;
        }
    }
}
