package mulin.tvdy.player;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import mulin.tvdy.R;
import mulin.tvdy.data.FeedVideo;

final class CreatorGridAdapter extends RecyclerView.Adapter<CreatorGridAdapter.Holder> {

    interface OnVideoSelectedListener {
        void onVideoSelected(int index);
    }

    private final List<FeedVideo> videos = new ArrayList<>();
    private OnVideoSelectedListener listener;

    void setOnVideoSelectedListener(OnVideoSelectedListener listener) {
        this.listener = listener;
    }

    void setVideos(List<FeedVideo> items) {
        videos.clear();
        if (items != null) {
            videos.addAll(items);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_creator_video, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        FeedVideo video = videos.get(position);
        SimpleImageLoader.load(video.coverUrl, holder.cover);
        String title = video.desc;
        if (title == null || title.isEmpty()) {
            title = "@" + (video.authorName != null ? video.authorName : "");
        }
        holder.title.setText(title);
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION) {
                listener.onVideoSelected(pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return videos.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;

        Holder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.creatorVideoCover);
            title = itemView.findViewById(R.id.creatorVideoTitle);
        }
    }
}
