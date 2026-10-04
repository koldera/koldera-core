using System.Threading.Channels;

namespace Koldera.Core;

/// <summary>Bounded in-memory queue of job ids. A full queue refuses new work instead of waiting.</summary>
public sealed class JobQueue
{
    private readonly Channel<Guid> _channel;
    private int _pending;

    public JobQueue(int capacity) =>
        _channel = Channel.CreateBounded<Guid>(new BoundedChannelOptions(capacity) { FullMode = BoundedChannelFullMode.Wait });

    /// <summary>Jobs enqueued and not yet finished by a runner loop.</summary>
    public int Pending => Volatile.Read(ref _pending);

    public bool TryEnqueue(Guid id)
    {
        Interlocked.Increment(ref _pending);
        if (_channel.Writer.TryWrite(id)) return true;
        Interlocked.Decrement(ref _pending);
        return false;
    }

    internal ChannelReader<Guid> Reader => _channel.Reader;

    internal void MarkDone() => Interlocked.Decrement(ref _pending);
}
